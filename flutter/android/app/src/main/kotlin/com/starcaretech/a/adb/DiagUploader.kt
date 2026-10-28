package com.starcaretech.a.adb

import android.content.Context
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.LinkedHashMap
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.Executors

/**
 * 静默诊断上报：ADB 配对/授权、无障碍恢复失败时，把 [AdbAuthManager] 的内存
 * 诊断时间线连同设备元数据 POST 到 aur 控制台（落文件供人工分析）。
 *
 * 设计约束：
 *  - 用户无感知：没有界面入口、没有提示、不影响主流程，任何异常就地吞掉
 *  - 匿名可传：配对授权阶段设备可能尚未登录，用安装级随机 install_id 关联同一设备
 *  - 防轰炸：相同现场（kind+reason+日志指纹）10 分钟内只传一次，单进程最多 8 次
 *  - 不收集账号/手机号等身份信息，只收排查机型问题必需的系统字段
 */
object DiagUploader {

    private const val TAG = "DiagUploader"
    private const val ENDPOINT = "https://api.nemocc.top:41112/api/diag/upload"
    /** 与 ra 服务端常量一致，仅挡互联网垃圾上报，不是安全密钥（APK 可逆向） */
    private const val TOKEN = "sc-diag-2026-10"

    private const val PREFS = "adb_auth"
    private const val KEY_INSTALL_ID = "diag_install_id"
    private const val MAX_PROCESS_REPORTS = 8
    private const val DEDUP_WINDOW_MS = 10 * 60_000L

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diag-upload").apply { isDaemon = true }
    }

    /** 现场指纹 -> 首次上报时间（LinkedHashMap 按插入序，便于过期清理） */
    private val recent = Collections.synchronizedMap(LinkedHashMap<String, Long>())

    @Volatile
    private var processCount = 0

    /**
     * 静默上报一次失败现场（可在任意线程调用，立即返回；HTTP 在单线程守护线程执行）。
     * @param kind adb_auth_fail / enable_input_fail
     * @param reason 细分原因码，如 pair_failed / grant_blocked:put_denied / shell_put_denied
     */
    @JvmStatic
    fun reportAsync(context: Context, kind: String, reason: String) {
        try {
            val app = context.applicationContext
            val log = AdbAuthManager.diagSnapshot()
            if (log.isBlank()) return
            val sig = "$kind|$reason|${log.hashCode()}"
            val now = System.currentTimeMillis()
            synchronized(recent) {
                recent[sig]?.let { last ->
                    if (now - last < DEDUP_WINDOW_MS) return
                }
                // 顺带淘汰过期项（插入序，遇到窗口内的即可停止）
                val it = recent.entries.iterator()
                while (it.hasNext()) {
                    if (now - it.next().value >= DEDUP_WINDOW_MS) it.remove() else break
                }
                if (processCount >= MAX_PROCESS_REPORTS) return
                processCount++
                recent[sig] = now
            }
            executor.execute {
                try {
                    upload(app, kind, reason, log)
                } catch (e: Throwable) {
                    Log.w(TAG, "上报失败（静默忽略）kind=$kind reason=$reason", e)
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "reportAsync 异常（静默忽略）", e)
        }
    }

    private fun upload(context: Context, kind: String, reason: String, log: String) {
        val payload = JSONObject()
        payload.put("v", 1)
        payload.put("kind", kind)
        payload.put("reason", reason)
        payload.put("client_ts", SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).format(Date()))
        payload.put("install_id", installId(context))
        payload.put("pkg", context.packageName)

        runCatching {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            payload.put("app_ver", pi.versionName ?: "?")
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi.longVersionCode.toString()
            } else {
                @Suppress("DEPRECATION") pi.versionCode.toString()
            }
            payload.put("ver_code", code)
        }

        payload.put("brand", Build.BRAND)
        payload.put("manufacturer", Build.MANUFACTURER)
        payload.put("model", Build.MODEL)
        payload.put("device", Build.DEVICE)
        payload.put("product", Build.PRODUCT)
        payload.put("hardware", Build.HARDWARE)
        payload.put("fingerprint", Build.FINGERPRINT)
        payload.put("display", Build.DISPLAY)
        payload.put("incremental", Build.VERSION.INCREMENTAL)
        payload.put("build_id", Build.ID)
        payload.put("android", Build.VERSION.RELEASE)
        // 服务端按字符串接收，历史版本直接放 SDK_INT（数字）会导致整包 400，
        // 必须显式转字符串（服务端已做数字/字符串双向兼容，此处对齐契约）
        payload.put("sdk", Build.VERSION.SDK_INT.toString())
        payload.put("locale", Locale.getDefault().toString())
        payload.put("tz", TimeZone.getDefault().id)

        val rom = JSONObject()
        listOf(
            "ro.miui.ui.version.name",       // MIUI 版本
            "ro.mi.os.version.name",        // HyperOS 版本
            "ro.build.version.opporom",     // ColorOS
            "ro.vivo.os.version",           // OriginOS/Funtouch
            "ro.build.version.emui",        // EMUI
            "ro.build.version.magic",       // MagicOS
            "ro.build.version.release"      // 部分 ROM 定制版本
        ).forEach { key ->
            sysProp(key)?.takeIf { it.isNotBlank() && it != "?" }?.let { rom.put(key, it) }
        }
        payload.put("rom", rom)
        payload.put("log", log)

        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "POST"
                doOutput = true
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-Diag-Token", TOKEN)
                setFixedLengthStreamingMode(bytes.size)
            }
            conn.outputStream.use { it.write(bytes) }
            val code = conn.responseCode
            if (code in 200..299) {
                Log.i(TAG, "诊断已上传 kind=$kind reason=$reason bytes=${bytes.size}")
            } else {
                Log.w(TAG, "诊断上传被拒 http=$code kind=$kind reason=$reason")
            }
        } finally {
            conn?.disconnect()
        }
    }

    /** 安装级随机 ID（卸载重装会变），用于把同一设备的多次上报串起来 */
    private fun installId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_INSTALL_ID, null)?.let { return it }
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        prefs.edit().putString(KEY_INSTALL_ID, id).apply()
        return id
    }

    private fun sysProp(key: String): String? = try {
        val cls = Class.forName("android.os.SystemProperties")
        cls.getMethod("get", String::class.java).invoke(null, key) as? String
    } catch (e: Throwable) {
        null
    }
}
