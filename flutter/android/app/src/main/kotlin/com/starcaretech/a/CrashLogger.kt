package com.starcaretech.a

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 崩溃日志落盘（无 root / 无电脑也能取到）：
 * 1. Java 未捕获异常：异常栈 + logcat(crash+main) 尾部写入 filesDir/crash/；
 * 2. 疑似 native 崩溃（Rust panic/SIGSEGV/SIGABRT，信号杀进程走不了 Java handler）：
 *    下次启动时读 logcat crash 缓冲，命中崩溃标志才落盘，避免每次启动都产生噪音；
 * 3. MainActivity 启动时通过 [consumeIfNew] 取未展示的崩溃文本，弹窗供用户分享。
 *
 * 同进程 exec logcat 从 Android 4.1 起只能读到本 uid 的日志，正好包含本进程的
 * FATAL EXCEPTION 与 Rust android_logger 输出；native tombstone 正文由系统进程
 * 打印读不到，但 crash 缓冲里的 "Fatal signal" 摘要行仍可作为定位线索。
 */
object CrashLogger {
    private const val TAG = "CrashLogger"
    private const val DIR_NAME = "crash"
    private const val PREF_NAME = "crash_pref"
    private const val KEY_WAS_RUNNING = "was_running"
    private const val KEY_LAST_SHOWN = "last_shown_file"
    private const val KEY_LAST_EXIT_TS = "last_exit_ts"
    private const val MAX_KEEP_FILES = 5
    private const val MAX_FILE_CHARS = 200_000

    fun install(app: Application) {
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { writeJavaCrash(app, thread, throwable) }
            previousHandler?.uncaughtException(thread, throwable)
        }

        val prefs = app.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val wasRunning = prefs.getBoolean(KEY_WAS_RUNNING, false)
        prefs.edit().putBoolean(KEY_WAS_RUNNING, true).apply()
        if (wasRunning) {
            // 上次没有走到"正常退出"标记：可能是 native 崩溃，也可能是被系统/用户杀进程。
            // 后台扫描 crash 缓冲，只有真的命中崩溃标志才落盘，避免误报。
            Thread {
                runCatching { captureSuspectNativeCrashIfAny(app) }
            }.start()
        }
    }

    private fun writeJavaCrash(ctx: Context, thread: Thread, e: Throwable) {
        val dir = crashDir(ctx)
        trimOldFiles(dir)
        val ts = timestamp()
        val file = File(dir, "crash_java_$ts.txt")
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val body = buildString {
            appendHeader(ctx, "java")
            append("thread: ").append(thread.name).append('\n')
            append("exception: ").append(e.javaClass.name)
                .append(": ").append(e.message).append("\n\n")
            append(sw.toString())
            append("\n\n----- logcat tail -----\n")
            append(dumpLogcat())
        }
        file.writeText(body.takeLast(MAX_FILE_CHARS))
        Log.e(TAG, "Java crash written to ${file.absolutePath}")
    }

    private fun captureSuspectNativeCrashIfAny(ctx: Context) {
        val log = dumpLogcat()
        val looksLikeCrash = CRASH_MARKERS.any { log.contains(it, ignoreCase = true) }
        if (!looksLikeCrash) return
        val dir = crashDir(ctx)
        // 连环死亡场景（如录屏授权后反复崩溃，每 7~12s 一轮）必须每次都落盘，
        // 仅保留最近 MAX_KEEP_FILES 份即可，不再按时间窗去重丢证据
        trimOldFiles(dir)
        val file = File(dir, "crash_native_${timestamp()}.txt")
        val body = buildString {
            appendHeader(ctx, "native(suspect)")
            append("note: 进程上次异常退出，以下为系统崩溃缓冲中的相关记录\n\n")
            append(log)
        }
        file.writeText(body.takeLast(MAX_FILE_CHARS))
        Log.i(TAG, "suspect native crash written to ${file.absolutePath}")
    }

    /**
     * 读取系统记录的"历史进程退出原因"（Android 11+，无需任何权限，且不依赖
     * 能否读 logcat——国产 ROM 静默杀进程/native 信号崩溃都能拿到原因码与
     * 系统给出的描述）。结果写入诊断时间线；属于崩溃/ANR 的另存 crash 文件，
     * 供崩溃弹窗展示给用户分享。
     */
    fun recordHistoricalExitReasons(ctx: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return
        try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
                ?: return
            val infos = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
            if (infos.isNullOrEmpty()) return
            val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val lastTs = prefs.getLong(KEY_LAST_EXIT_TS, 0L)
            var maxTs = lastTs
            val fresh = infos.filter { it.timestamp > lastTs }
            if (fresh.isEmpty()) return
            val lines = fresh.map { info ->
                if (info.timestamp > maxTs) maxTs = info.timestamp
                val time = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))
                buildString {
                    append("EXIT-REASON time=$time pid=${info.pid} reason=${exitReasonName(info.reason)}")
                    append(" sub=${info.subReason} importance=${info.importance} status=${info.status}")
                    info.description?.takeIf { it.isNotBlank() }?.let { append(" desc=$it") }
                    // ANR/部分 native 崩溃系统会附一段 trace（如 ANR 主线程栈）
                    runCatching {
                        info.traceInputStream?.bufferedReader()?.use { it.readText() }
                            ?.take(4000)?.takeIf { t -> t.isNotBlank() }
                    }.getOrNull()?.let { append("\n--- trace ---\n").append(it) }
                }
            }
            prefs.edit().putLong(KEY_LAST_EXIT_TS, maxTs).apply()
            // 1) 进诊断时间线（用户每次都能在 adb_auth_diag.log 看到）
            lines.forEach { com.starcaretech.a.adb.AdbAuthManager.trace(ctx, it) }
            // 2) 崩溃类原因另存 crash 目录，让崩溃分享弹窗能带上
            val fatal = fresh.any { it.reason in FATAL_EXIT_REASONS }
            if (fatal) {
                val dir = crashDir(ctx)
                trimOldFiles(dir)
                val file = File(dir, "crash_exit_${timestamp()}.txt")
                val body = buildString {
                    appendHeader(ctx, "exit-reason")
                    lines.joinToString("\n\n").also { append(it) }
                    append("\n\n----- logcat tail -----\n")
                    append(dumpLogcat())
                }
                file.writeText(body.takeLast(MAX_FILE_CHARS))
                Log.i(TAG, "fatal exit reason written to ${file.absolutePath}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "recordHistoricalExitReasons fail", e)
        }
    }

    private fun exitReasonName(reason: Int): String = when (reason) {
        android.app.ApplicationExitInfo.REASON_EXIT_UNKNOWN -> "UNKNOWN($reason)"
        android.app.ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED($reason)"
        android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY($reason)"
        android.app.ApplicationExitInfo.REASON_CRASH -> "CRASH(Java,$reason)"
        android.app.ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE($reason)"
        android.app.ApplicationExitInfo.REASON_ANR -> "ANR($reason)"
        android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE($reason)"
        android.app.ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE($reason)"
        android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USE -> "EXCESSIVE_RESOURCE($reason)"
        android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED($reason)"
        android.app.ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED($reason)"
        android.app.ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED($reason)"
        android.app.ApplicationExitInfo.REASON_OTHER -> "OTHER($reason)"
        // 15=REASON_FREEZER(API31)、16=REASON_PACKAGE_UPDATED(API33)，
        // 用字面量避免 API30 设备访问高版本静态字段
        15 -> "FREEZER(15)"
        16 -> "PACKAGE_UPDATED(16)"
        else -> "REASON_$reason"
    }

    private fun StringBuilder.appendHeader(ctx: Context, type: String): StringBuilder {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        val versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pi.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            pi.versionCode.toLong()
        }
        append("crash_type: ").append(type).append('\n')
        append("package: ").append(ctx.packageName).append('\n')
        append("version: ").append(pi.versionName).append(" (").append(versionCode).append(")\n")
        append("device: ").append(android.os.Build.MANUFACTURER).append(' ')
            .append(android.os.Build.MODEL).append('\n')
        append("android: ").append(android.os.Build.VERSION.RELEASE)
            .append(" (sdk ").append(android.os.Build.VERSION.SDK_INT).append(")\n")
        append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())).append("\n\n")
        return this
    }

    private fun dumpLogcat(): String {
        return try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-b", "crash", "-b", "main", "-v", "time", "-t", "2000")
            )
            val text = process.inputStream.bufferedReader().readText()
            process.waitFor(3, TimeUnit.SECONDS)
            process.destroy()
            text
        } catch (e: Exception) {
            "(logcat unavailable: ${e.message})"
        }
    }

    private fun crashDir(ctx: Context): File =
        File(ctx.filesDir, DIR_NAME).apply { if (!exists()) mkdirs() }

    private fun trimOldFiles(dir: File) {
        dir.listFiles()
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_KEEP_FILES)
            ?.forEach { runCatching { it.delete() } }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 取出尚未对用户展示过的最新崩溃日志全文；同一份只返回一次。 */
    fun consumeIfNew(ctx: Context): String? {
        val dir = File(ctx.filesDir, DIR_NAME)
        val newest = dir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".txt") }
            ?.maxByOrNull { it.lastModified() }
            ?: return null
        val prefs = ctx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_LAST_SHOWN, null) == newest.name) return null
        prefs.edit().putString(KEY_LAST_SHOWN, newest.name).apply()
        return runCatching { newest.readText() }.getOrNull()
    }
}

private val CRASH_MARKERS = listOf(
    "FATAL EXCEPTION",
    "Fatal signal",
    "*** *** ***",
    "SIGSEGV",
    "SIGABRT",
    "panicked at",
    "RUST_BACKTRACE",
    // FGS / 录屏授权违规导致的进程级异常（Android 12+）
    "ForegroundServiceStartNotAllowed",
    "ForegroundServiceDidNotStartInTimeException",
    "ForegroundServiceDidNotStopInTimeException",
    "RemoteServiceException",
    "MediaProjection",
    "ANR in"
)

/** 系统历史退出原因中视为"崩溃"的类型（另存 crash 文件并对用户弹窗） */
private val FATAL_EXIT_REASONS = setOf(
    android.app.ApplicationExitInfo.REASON_CRASH,
    android.app.ApplicationExitInfo.REASON_CRASH_NATIVE,
    android.app.ApplicationExitInfo.REASON_ANR,
    android.app.ApplicationExitInfo.REASON_SIGNALED,
    android.app.ApplicationExitInfo.REASON_INITIALIZATION_FAILURE
)
