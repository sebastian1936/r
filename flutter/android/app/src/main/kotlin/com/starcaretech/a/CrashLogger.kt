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
        // Java 崩溃时 handler 已写过一份，5 分钟内不重复写 native 疑似文件
        val newest = dir.listFiles()?.maxByOrNull { it.lastModified() }
        if (newest != null && System.currentTimeMillis() - newest.lastModified() < 5 * 60_000) return
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
    "RUST_BACKTRACE"
)
