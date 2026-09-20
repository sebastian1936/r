package com.starcaretech.a.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.starcaretech.a.InputService

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
     * @param guid 配对返回的设备 guid
     * @param shellDirect true=未能 pm grant（ROM 限制），已退而用 shell 直接写 secure settings
     *                    开启无障碍；此模式下 App 自身没有 WRITE_SECURE_SETTINGS，进程被杀后
     *                    无法自愈，需要重新跑一次本流程
     */
    data class GrantResult(val guid: String, val shellDirect: Boolean)

    private const val MARKER = "__ADB_SHELL_MARKER__"

    /** 把异常链（含异常类型）展开成可展示文本 */
    private fun Throwable?.chainText(): String {
        val sb = StringBuilder()
        var cur: Throwable? = this
        var depth = 0
        while (cur != null && depth < 6) {
            if (depth > 0) sb.append("\n  ↳ ")
            sb.append(cur.javaClass.name).append(": ").append(cur.message ?: "(无错误信息)")
            cur = cur.cause
            depth++
        }
        return sb.toString()
    }

    /**
     * 功能是否可用：
     * - "无线调试"（配对码页面 + mDNS 配对服务 + SPAKE2 协议）是 Android 11（API 30）引入的，
     *   Android 10 及以下系统没有该入口，不应展示本功能
     * - HarmonyOS 2/3/4 虽报 API 30/31，但华为在开发者选项中阉割了"无线调试"
     *   （Shizuku 官方亦列为不支持机型），配对流程无法走通，同样视为不支持，
     *   UI 降级为传统手动授权；HarmonyOS NEXT 不支持 APK，应用无法安装，无需判断
     */
    fun isSupported(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !HarmonyOsDetector.isHarmonyOs

    /** 不可用时的提示文案（可用时返回 null），供 UI 直接展示 */
    fun unsupportedReason(): String? =
        if (isSupported()) null else "该功能需要 Android 11 及以上系统（需系统支持无线调试）"

    /** 一轮 shell 执行结果：连接都失败时 connected=false（attempts 为各次原因） */
    private class ShellRun(
        val connected: Boolean,
        val output: String = "",
        val attempts: List<String> = emptyList()
    )

    /**
     * 完整闭环：配对 → 授权 → 验证。
     * @param pairingCode 6 位配对码
     * @param pairingHost 配对服务地址（一般 127.0.0.1）
     * @param pairingPort 系统无线调试配对页显示的端口（或 mDNS 扫描得到）
     */
    fun pairAndGrant(context: Context, pairingCode: String, pairingHost: String = "127.0.0.1", pairingPort: Int): GrantResult {
        val identity = AdbKeyStore.getOrCreate(context)

        // 1. 配对（成功后设备保存我们的公钥，并开放 TLS connect 服务）
        val guid = try {
            AdbPairingClient.pair(identity, pairingHost, pairingPort, pairingCode)
        } catch (e: Exception) {
            Log.w(TAG, "配对失败", e)
            throw AuthException(
                "配对失败：请确认 6 位配对码正确、配对页仍在显示后重试\n${e.chainText()}",
                e
            )
        }

        // 配对刚完成，connect 服务可能还没就绪，等一下再连
        Thread.sleep(2000)

        // 2. pm grant（标准路径）。connect 端口每轮都重新 mDNS 发现，
        //    自动跳过 NsdManager 的陈旧记录。
        val grantRun = runShellWithRetry(context, identity) {
            "pm grant ${context.packageName} $PERM_WRITE_SECURE_SETTINGS"
        }
        if (!grantRun.connected) {
            throw AuthException(
                "配对成功，但无法连接无线调试服务。请保持「无线调试」开启后重试。\n\n" +
                grantRun.attempts.joinToString("\n\n"),
                null
            )
        }

        val grantOut = grantRun.output.trim()
        if (grantOut.isEmpty()) {
            // 3a. 标准路径：验证 WRITE_SECURE_SETTINGS 落库
            var granted = isWriteSecureSettingsGranted(context)
            if (!granted) {
                for (i in 1..2) {
                    Thread.sleep(1500)
                    granted = isWriteSecureSettingsGranted(context)
                    if (granted) break
                }
            }
            if (granted) {
                saveResult(context, guid, "pm_grant")
                Log.i(TAG, "ADB 自授权完成（pm grant）guid=$guid")
                return GrantResult(guid, shellDirect = false)
            }
            // 输出为空但权限没落下：落到兜底再试一次
            Log.w(TAG, "pm grant 无输出但权限未生效，转 shell 直写兜底")
        }

        // 3b. 兜底：很多国产 ROM（MIUI/HyperOS/ColorOS 等）只拦截 pm grant
        //     （shell 无 GRANT_RUNTIME_PERMISSIONS），但 shell 自身仍持有
        //     WRITE_SECURE_SETTINGS，可以直接 settings put secure 开启无障碍——
        //     Shizuku 生态的免 root 工具普遍用这个通道。
        val directOut = try {
            shellDirectEnableAccessibility(context, identity)
        } catch (e: Exception) {
            Log.w(TAG, "shell 直写兜底失败", e)
            null
        }
        if (directOut == true) {
            saveResult(context, guid, "shell_direct")
            Log.i(TAG, "ADB 授权完成（shell 直写兼容模式）guid=$guid")
            return GrantResult(guid, shellDirect = true)
        }

        // 4. 两条路都失败：按特征给出对应操作提示（原始错误只进日志）
        Log.w(TAG, "授权失败，pm grant 输出：$grantOut")
        val romHint = when {
            grantOut.contains("GRANT_RUNTIME_PERMISSIONS") -> buildRomSecurityHint(grantOut)
            grantOut.isEmpty() ->
                "授权未生效，请关闭「无线调试」后重新打开，再重新配对；仍失败请重启手机后重试"
            else -> "授权失败，请重试。\n$grantOut"
        }
        throw AuthException(romHint)
    }

    /**
     * 最多 3 轮：每轮重新发现 connect 端口（端口会变/陈旧记录要跳过），
     * 连接成功即返回命令输出；3 次都连不上才判定连接失败。
     */
    private fun runShellWithRetry(
        context: Context,
        identity: AdbKeyStore.Identity,
        command: () -> String
    ): ShellRun {
        val cmd = command()
        val attempts = mutableListOf<String>()
        for (attempt in 1..3) {
            val connect = discoverConnect(context)
            if (connect == null) {
                Log.w(TAG, "第 $attempt/3 次未发现有效的 connect 服务")
                attempts.add(
                    "第 $attempt 次：mDNS 未发现有效的 connect 服务（无记录或端口未监听）。\n" +
                    "请确认系统「无线调试」开关仍处于开启状态，然后重试"
                )
                if (attempt < 3) Thread.sleep(2000)
                continue
            }
            Log.i(TAG, "第 $attempt/3 次连接 adbd ${connect.host}:${connect.port}（来源:${connect.serviceName}）")
            try {
                val output = AdbConnection.shell(
                    identity, connect.host, connect.port, cmd, timeoutMs = 10_000
                )
                return ShellRun(connected = true, output = output)
            } catch (e: Exception) {
                Log.w(TAG, "连接 adbd 失败 尝试 $attempt/3 ${connect.host}:${connect.port}", e)
                attempts.add("第 $attempt 次（${connect.host}:${connect.port}，来源:${connect.serviceName}）:\n${e.chainText()}")
                if (attempt < 3) Thread.sleep(2000)
            }
        }
        return ShellRun(connected = false, attempts = attempts)
    }

    /**
     * shell 直写开启无障碍。在 adb shell 内用一条复合命令完成"读-改-写-校验"，
     * 保留名单里其他应用的服务。返回 true 表示系统名单已包含我们的组件。
     * @param force true=名单中已有组件时也先移除再加回，强制系统重新绑定
     *              （开关显示开启但服务实际未运行的国产 ROM 场景）
     */
    private fun shellDirectEnableAccessibility(
        context: Context,
        identity: AdbKeyStore.Identity,
        force: Boolean = false
    ): Boolean {
        val comp = accessibilityComponent(context)
        // 组件名只含 [a-z0-9./]，直接内联安全；其余全部用 sh 变量，避免转义问题
        val forceFlag = if (force) "1" else "0"
        val script = """
            old=${'$'}(settings get secure enabled_accessibility_services)
            target='$comp'
            force='$forceFlag'
            nval=""
            found=0
            saved_ifs="${'$'}IFS"
            IFS=':'
            for item in ${'$'}old; do
              if [ "${'$'}item" = "${'$'}target" ]; then
                found=1
              else
                case "${'$'}item" in ""|"null") ;; *) nval="${'$'}{nval:+${'$'}nval:}${'$'}item";; esac
              fi
            done
            IFS="${'$'}saved_ifs"
            if [ "${'$'}found" = "1" ] && [ "${'$'}force" != "1" ]; then
              nval="${'$'}old"
            else
              nval="${'$'}{nval:+${'$'}nval:}${'$'}target"
            fi
            settings put secure accessibility_enabled 1
            settings put secure enabled_accessibility_services "${'$'}nval"
            echo $MARKER
            settings get secure enabled_accessibility_services
        """.trimIndent()

        val run = runShellWithRetry(context, identity) { script }
        if (!run.connected) {
            Log.w(TAG, "shell 直写时连接 adbd 失败：\n${run.attempts.joinToString("\n")}")
            return false
        }
        val out = run.output
        if (out.contains("SecurityException") || out.contains("Permission denial")) {
            Log.w(TAG, "shell 直写被系统拒绝：$out")
            return false
        }
        val afterMarker = out.substringAfter(MARKER, "")
        val listed = afterMarker.split('\n', ':', ' ').any { it.trim() == comp }
        if (!listed) return false

        // 系统设置落库后再用 App 侧视角确认一次（最多等 3 秒）
        if (isAccessibilityListed(context)) return true
        for (i in 1..2) {
            Thread.sleep(1500)
            if (isAccessibilityListed(context)) return true
        }
        return false
    }

    private fun saveResult(context: Context, guid: String, mode: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("granted", mode == "pm_grant")
            .putString("grant_mode", mode)
            .putString("guid", guid)
            .putLong("granted_at", System.currentTimeMillis())
            // 配对授权刚完成：App 回前台后应引导一次系统录屏授权（一次性标记，
            // 由 consumeCapturePending 读取并清除，不重复骚扰）
            .putBoolean("capture_pending", true)
            .apply()
    }

    /**
     * 授权是否已生效（三重判据，任一成立即可）：
     *  - App 持有 WRITE_SECURE_SETTINGS（pm_grant 路径，持久）
     *  - 无障碍服务在系统名单中（shell_direct 路径，持久，服务可能被杀但名单在）
     *  - InputService 实际正在运行（同进程静态事实，兜住部分 ROM 对 secure
     *    setting 读取延迟/隔离导致的名单误判）
     */
    fun isEnabled(context: Context): Boolean =
        isWriteSecureSettingsGranted(context) ||
            isAccessibilityListed(context) ||
            InputService.isOpen

    /** 曾经成功完成过一次配对授权（grant_mode 有值即配对过，持久事实，
     *  与无障碍当前是否运行、是否被 disableSelf 无关）。
     *  注意 pm_grant / shell_direct 两种模式都会写入 grant_mode。 */
    fun isPaired(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("grant_mode", null) != null

    /** 授权诊断明细（通道日志/排查用） */
    fun statusSnapshot(context: Context): Map<String, Any?> = mapOf(
        "wss" to isWriteSecureSettingsGranted(context),
        "a11y_listed" to isAccessibilityListed(context),
        "a11y_running" to InputService.isOpen,
        "paired" to isPaired(context),
        "a11y_list_raw" to runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
        }.getOrNull(),
        "component" to accessibilityComponent(context)
    )

    /** 只读查看"待引导录屏授权"标记（供状态通道使用，不清标记） */
    fun peekCapturePending(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("capture_pending", false)

    /** 读取并清除"待引导录屏授权"标记，保证只自动引导一次 */
    fun consumeCapturePending(context: Context): Boolean {
        val sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val pending = sp.getBoolean("capture_pending", false)
        if (pending) sp.edit().putBoolean("capture_pending", false).apply()
        return pending
    }

    /** GRANT_RUNTIME_PERMISSIONS 特征错误：国产 ROM 的"USB 调试安全设置"未开。
     *  只给操作步骤，不讲原理；原始错误仅进日志 */
    private fun buildRomSecurityHint(rawError: String): String {
        Log.w(TAG, "ROM 安全开关未开，原始错误：\n$rawError")
        return "配对成功，但系统禁止 ADB 授权。请到「开发者选项」打开对应开关后重试：\n" +
            "· 小米 / 红米：USB 调试（安全设置）\n" +
            "· OPPO / 一加 / realme：USB 调试（安全设置），或关闭「权限监控」\n" +
            "· vivo / iQOO：USB 模拟点击\n" +
            "· 华为 / 荣耀：仅充电模式下允许 ADB 调试\n" +
            "· 其他品牌：在开发者选项中找「安全 / 权限 / 模拟点击」类开关"
    }

    /** 自动扫描配对服务再走完整流程（需要用户停在配对码页面） */
    fun pairAndGrantAuto(context: Context, pairingCode: String): GrantResult {
        val service = AdbDiscovery.findFirst(context, AdbDiscovery.TYPE_PAIRING, timeoutMs = 20_000)
            ?: throw AuthException("未发现配对服务：请确认无线调试配对码页面已打开")
        // host 强制 127.0.0.1（本机连本机）
        return pairAndGrant(context, pairingCode, "127.0.0.1", service.port)
    }

    fun isWriteSecureSettingsGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, PERM_WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /** InputService 当前是否在系统无障碍名单中（只读安全设置）。
     *  同时兼容全名（pkg/pkg.InputService）与短名（pkg/.InputService）写法，
     *  忽略大小写与空白，适配各 ROM 二次确认后对名单的规范化改写 */
    fun isAccessibilityListed(context: Context): Boolean = try {
        val current = Settings.Secure.getString(
            context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.lowercase().orEmpty()
        val pkg = context.packageName.lowercase()
        val targets = setOf("$pkg/$pkg.inputservice", "$pkg/.inputservice")
        current.split(':').any { it.trim() in targets }
    } catch (e: Exception) {
        Log.w(TAG, "读取无障碍名单失败", e)
        false
    }

    /**
     * 一键打开输入控制：配对授权成功后，用户在页面上打开输入开关时直接启用无障碍，
     * 不再跳转系统设置。必须在后台线程调用（shell 路径最多阻塞约 20 秒）。
     *
     * 路径优先级：
     *  1. InputService 已在运行 → 直接成功
     *  2. App 持有 WRITE_SECURE_SETTINGS（pm_grant 模式，离线可用）→ 本地直写，
     *     名单在但未绑定时强制重绑
     *  3. 曾配对成功（shell_direct 模式）→ 连本机 adbd 直写（需无线调试开着）
     *  4. 从未配对 / 无线调试关闭连不上 → 返回失败，调用方回退系统设置引导
     */
    fun enableInput(context: Context): EnableResult {
        // 1) 已运行
        if (InputService.isOpen) return EnableResult(true, "already")

        val listed = isAccessibilityListed(context)

        // 2) App 自身有权限：本地写 secure settings，离线、瞬时
        if (isWriteSecureSettingsGranted(context)) {
            val ok = if (listed) {
                // 名单在但服务没起来：先移除再加回触发系统重绑（约 800ms）
                Log.i(TAG, "enableInput：名单在但未绑定，强制重绑")
                forceRebindAccessibility(context)
            } else {
                repairAccessibility(context)
            }
            return if (ok) EnableResult(true, "local") else EnableResult(false, "local_failed")
        }

        // 3) 没本地权限：必须配对过，且此刻无线调试开着才能连 adbd
        val mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("grant_mode", null)
        if (mode == null) {
            return EnableResult(false, "not_paired")
        }
        return try {
            val identity = AdbKeyStore.getOrCreate(context)
            // 名单在但没绑定 → force 强制重绑；不在名单 → 普通追加
            val ok = shellDirectEnableAccessibility(context, identity, force = listed)
            if (ok) EnableResult(true, "shell") else EnableResult(false, "shell_failed")
        } catch (e: Exception) {
            Log.w(TAG, "enableInput shell 路径异常", e)
            EnableResult(false, "shell_failed")
        }
    }

    /**
     * @param ok 是否成功把 InputService 写入系统名单（系统绑定可能滞后 1~2 秒）
     * @param mode already/local/shell；失败时为原因码 not_paired/local_failed/shell_failed
     */
    data class EnableResult(val ok: Boolean, val mode: String)

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
                writeAccessibilityList(cr, services)
            }
            Log.i(TAG, "无障碍自愈${if (changed) "已执行" else "无需变更"}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "无障碍自愈失败", e)
            false
        }
    }

    /**
     * 强制重绑：先把 InputService 从名单移除，等系统完成解绑，再加回。
     *
     * 适用场景：组件仍在 ENABLED_ACCESSIBILITY_SERVICES 名单里，但系统已解绑服务
     * （很多国产 ROM 的实际表现：无障碍开关显示开启，但 InputService 不在运行）。
     * 同名重复 putString 不会触发 AccessibilityManagerService 重新绑定，必须先移除再加回。
     *
     * 本方法会阻塞约 800ms（等待系统响应名单变更），只能在后台线程调用。
     */
    fun forceRebindAccessibility(context: Context): Boolean {
        if (!isWriteSecureSettingsGranted(context)) return false
        return try {
            val cr = context.contentResolver
            val component = accessibilityComponent(context)
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
            val others = current.split(':').filter { it.isNotEmpty() && it != component }

            // 1) 移除我方组件并提交，触发系统解绑
            writeAccessibilityList(cr, others)
            Log.i(TAG, "强制重绑：已从无障碍名单移除 InputService")
            // 2) 等待 AccessibilityManagerService 处理变更（不能太短，否则两次写入可能被合并）
            Thread.sleep(800)
            // 3) 加回，触发系统重新绑定
            writeAccessibilityList(cr, others + component)
            Log.i(TAG, "强制重绑：已写回 InputService，等待系统重新绑定")
            true
        } catch (e: Exception) {
            Log.w(TAG, "强制重绑无障碍失败", e)
            false
        }
    }

    private fun writeAccessibilityList(cr: android.content.ContentResolver, services: List<String>) {
        Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, services.joinToString(":"))
        Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
    }

    private fun discoverConnect(context: Context): AdbDiscovery.DiscoveredService? {
        // 只从 mDNS 获取端口，host 强制 127.0.0.1（本机连本机回环最可靠）。
        // findFirst 已做"本机网卡地址 + 127.0.0.1 端口存活"双重校验，自动跳过陈旧记录；
        // 分多轮扫描，覆盖配对完成后 connect 服务刚注册的时间窗。
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            val s = AdbDiscovery.findFirst(context, AdbDiscovery.TYPE_CONNECT, timeoutMs = 5_000)
            if (s != null) {
                return AdbDiscovery.DiscoveredService(s.serviceName, "127.0.0.1", s.port, s.attributes)
            }
        }
        // 兜底：部分 ROM（或旧版无线调试）监听固定 5555，同样要求端口真的在监听
        return if (AdbDiscovery.isLoopbackPortListening(5555))
            AdbDiscovery.DiscoveredService("fallback", "127.0.0.1", 5555, emptyMap())
        else null
    }
}

/**
 * HarmonyOS 识别（只针对仍兼容 APK 的 HarmonyOS 2/3/4；
 * HarmonyOS NEXT 无安卓运行环境、APK 无法安装，不需要也执行不到这里）。
 *
 * 三重判据，命中任一即认定（仅华为机型会命中）：
 *  1. 系统属性 hw_sc.build.platform.version 非空（值如 2.0.0 / 3.0.0 / 4.0.0）
 *  2. 华为内部类 com.huawei.system.BuildEx.getOsBrand() 返回 "harmony"
 *  3. Build.DISPLAY 含 "HarmonyOS" 字样（较新版本）
 */
object HarmonyOsDetector {
    val isHarmonyOs: Boolean by lazy {
        (runCatching {
            val clz = Class.forName("android.os.SystemProperties")
            val m = clz.getMethod("get", String::class.java)
            (m.invoke(null, "hw_sc.build.platform.version") as? String).orEmpty()
        }.getOrDefault("").isNotEmpty()) ||
            (runCatching {
                val clz = Class.forName("com.huawei.system.BuildEx")
                clz.getMethod("getOsBrand").invoke(null) as? String
            }.getOrNull()?.equals("harmony", ignoreCase = true) == true) ||
            Build.DISPLAY?.contains("HarmonyOS", ignoreCase = true) == true
    }
}
