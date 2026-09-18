package com.starcaretech.a.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * ADB 自授权门面：配对 → pm grant → 验证 →（可选）无障碍自愈。
 *
 * 使用方式（全部在后台线程执行）：
 *  1. 用户在系统"开发者选项 → 无线调试 → 使用配对码配对设备"页面拿到 6 位码和 端口
 *  2. [pairAndGrant]（或先用 [AdbDiscovery] 扫到配对服务端口）
 *  3. 之后随时可用 [repairAccessibility] 恢复被系统杀掉的无障碍服务
 *     （WRITE_SECURE_SETTINGS 授权一次后，重启/升级 App 都不会丢失）
 */
object AdbAuthManager {

    private const val TAG = "AdbAuthManager"
    private const val PREFS = "adb_auth"

    /** 目标权限：signature|privileged|development，shell 可以 grant */
    private const val PERM_WRITE_SECURE_SETTINGS = "android.permission.WRITE_SECURE_SETTINGS"

    /** 本应用无障碍服务组件（manifest: android:name=".InputService"） */
    fun accessibilityComponent(context: Context): String =
        "${context.packageName}/${context.packageName}.InputService"

    class AuthException(message: String, cause: Throwable? = null) : Exception(message, cause)

    /**
     * 功能是否可用：
     * - "无线调试"（配对码页面 + mDNS 配对服务 + SPAKE2 协议）是 Android 11（API 30）引入的，
     *   Android 10 及以下系统没有该入口，不应展示本功能
     * - HarmonyOS 2/3/4（可安装 APK）底层仍为 AOSP，开发者选项中有无线调试，可用；
     *   HarmonyOS NEXT 不支持 APK，应用无法安装，无需在此判断
     */
    fun isSupported(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** 不可用时的提示文案（可用时返回 null），供 UI 直接展示 */
    fun unsupportedReason(): String? =
        if (isSupported()) null else "该功能需要 Android 11 及以上系统（需系统支持无线调试）"

    /**
     * 完整闭环：配对 → 授权 → 验证。
     * @param pairingCode 6 位配对码
     * @param pairingHost 配对服务地址（一般 127.0.0.1）
     * @param pairingPort 系统无线调试配对页显示的端口（或 mDNS 扫描得到）
     * @return 设备 guid
     */
    fun pairAndGrant(context: Context, pairingCode: String, pairingHost: String = "127.0.0.1", pairingPort: Int): String {
        val identity = AdbKeyStore.getOrCreate(context)

        // 1. 配对（成功后设备保存我们的公钥，并开放 TLS connect 服务）
        val guid = try {
            AdbPairingClient.pair(identity, pairingHost, pairingPort, pairingCode)
        } catch (e: Exception) {
            throw AuthException("配对失败：请确认配对码正确且页面停留在此界面", e)
        }

        // 配对刚完成，connect 服务可能还没就绪，等一下再连
        Thread.sleep(2000)

        // 2. 找 TLS connect 端口（mDNS 获取端口，host 强制 127.0.0.1 本机回环）
        val connect = discoverConnect(context)

        // 3. pm grant（连接失败重试 3 次，每次间隔 2s）
        var lastError: Exception? = null
        var output = ""
        for (attempt in 1..3) {
            try {
                output = AdbConnection.shell(
                    identity,
                    connect.host,
                    connect.port,
                    "pm grant ${context.packageName} $PERM_WRITE_SECURE_SETTINGS",
                    timeoutMs = 10_000
                )
                lastError = null
                break
            } catch (e: Exception) {
                lastError = e
                Log.w(TAG, "连接 adbd 失败 尝试 $attempt/3 host=${connect.host}:${connect.port}", e)
                if (attempt < 3) Thread.sleep(2000)
            }
        }
        if (lastError != null) {
            throw AuthException("已配对但连接 adbd 失败（host=${connect.host}:${connect.port}）", lastError)
        }
        val trimmed = output.trim()
        if (trimmed.isNotEmpty()) {
            // pm grant 失败会输出异常信息
            throw AuthException("pm grant 未成功：$trimmed")
        }

        // 4. 验证
        if (!isWriteSecureSettingsGranted(context)) {
            throw AuthException("授权流程完成但权限未生效")
        }

        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("granted", true)
            .putString("guid", guid)
            .putLong("granted_at", System.currentTimeMillis())
            .apply()
        Log.i(TAG, "ADB 自授权完成 guid=$guid")
        return guid
    }

    /** 自动扫描配对服务再走完整流程（需要用户停在配对码页面） */
    fun pairAndGrantAuto(context: Context, pairingCode: String): String {
        val service = AdbDiscovery.findFirst(context, AdbDiscovery.TYPE_PAIRING, timeoutMs = 20_000)
            ?: throw AuthException("未发现配对服务：请确认无线调试配对码页面已打开")
        // host 强制 127.0.0.1（本机连本机）
        return pairAndGrant(context, pairingCode, "127.0.0.1", service.port)
    }

    fun isWriteSecureSettingsGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERM_WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * 无障碍自愈：把本应用的 InputService 写回系统无障碍开关（需要已获得 WRITE_SECURE_SETTINGS）。
     * 保留列表中其他应用的服务，只确保我们的服务在列。
     */
    fun repairAccessibility(context: Context): Boolean {
        if (!isWriteSecureSettingsGranted(context)) return false
        return try {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val component = accessibilityComponent(context)
            val services = current.split(':').filter { it.isNotEmpty() }.toMutableList()
            val changed = if (services.contains(component)) false
            else {
                services.add(component)
                true
            }
            if (changed) {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            Log.i(TAG, "无障碍自愈${if (changed) "已执行" else "无需变更"}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "无障碍自愈失败", e)
            false
        }
    }

    private fun discoverConnect(context: Context): AdbDiscovery.DiscoveredService {
        // 只从 mDNS 获取端口，host 强制 127.0.0.1（本机连本机回环最可靠）
        AdbDiscovery.findFirst(context, AdbDiscovery.TYPE_CONNECT, timeoutMs = 15_000)?.let {
            return AdbDiscovery.DiscoveredService(it.serviceName, "127.0.0.1", it.port, it.attributes)
        }
        // 兜底：部分 ROM（或旧版无线调试）监听固定 5555
        return AdbDiscovery.DiscoveredService("fallback", "127.0.0.1", 5555, emptyMap())
    }
}
