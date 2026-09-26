package com.starcaretech.a.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.starcaretech.a.InputService
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /**
     * 名单写入后等待系统真正绑定 InputService 的最长时间。
     * AOSP 通常 1~2s；MIUI 等国产 ROM 在 disableSelf 后重新写名单时，
     * 只恢复开关显示而不绑定的情况也靠这段轮询识别出来。
     */
    private const val AWAIT_BIND_MS = 5_000L

    /** 授权失败诊断文件名（位于 App files 目录，可直接从 Android/data 取出） */
    private const val DIAG_FILE = "adb_auth_diag.log"
    private const val DIAG_MAX_BYTES = 256 * 1024L

    /** 轮询等待 InputService 完成 onServiceConnected（只能在后台线程调用） */
    private fun awaitBound(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (InputService.isOpen) return true
            if (System.currentTimeMillis() >= deadline) return false
            try {
                Thread.sleep(200)
            } catch (e: InterruptedException) {
                return InputService.isOpen
            }
        }
    }

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
     * shell 直写无障碍的分阶段结果，便于 UI 给出针对性指引：
     *  - no_service：mDNS 找不到 connect 服务（无线调试页面没停留/服务没起来）
     *  - rejected：TLS 握手或 ADB 认证被拒（配对钥匙失效，需要重新配对）
     *  - connect_error：其他连接错误（端口有响应但协议失败等）
     *  - put_denied：shell 写 secure settings 被 ROM 拒绝（小米"USB 调试安全设置"）
     *  - verify_failed：命令执行了但系统名单里没有我们的组件
     *  - ok：成功
     */
    private class DirectResult(val ok: Boolean, val stage: String, val detail: String) {
        companion object {
            fun ok() = DirectResult(true, "ok", "")
        }
    }

    /**
     * 完整闭环：配对 → 授权 → 验证。
     * @param pairingCode 6 位配对码
     * @param pairingHost 配对服务地址（一般 127.0.0.1）
     * @param pairingPort 系统无线调试配对页显示的端口（或 mDNS 扫描得到）
     */
    fun pairAndGrant(context: Context, pairingCode: String, pairingHost: String = "127.0.0.1", pairingPort: Int): GrantResult {
        val identity = AdbKeyStore.getOrCreate(context)
        diag(context, buildString {
            appendLine("pairAndGrant start pairingPort=$pairingPort")
            appendLine("brand=${Build.BRAND} manufacturer=${Build.MANUFACTURER} model=${Build.MODEL} device=${Build.DEVICE}")
            appendLine("android=${Build.VERSION.RELEASE} sdk=${Build.VERSION.SDK_INT} incr=${Build.VERSION.INCREMENTAL}")
            appendLine("display=${Build.DISPLAY}")
            runCatching {
                val clz = Class.forName("android.os.SystemProperties")
                val m = clz.getMethod("get", String::class.java)
                listOf(
                    "ro.mi.os.version.name", "ro.mi.os.version.code",
                    "ro.miui.ui.version.name", "ro.miui.ui.version.code",
                    "ro.build.version.incremental"
                ).forEach { appendLine("$it=${m.invoke(null, it)}") }
            }
            appendLine("before: wss=${isWriteSecureSettingsGranted(context)} listed=${isAccessibilityListed(context)} running=${InputService.isOpen}")
        })

        // 1. 配对（成功后设备保存我们的公钥，并开放 TLS connect 服务）
        val guid = try {
            AdbPairingClient.pair(identity, pairingHost, pairingPort, pairingCode)
        } catch (e: Exception) {
            Log.w(TAG, "配对失败", e)
            diag(context, "pair failed: ${e.chainText()}")
            throw AuthException(
                "配对失败：请确认 6 位配对码正确、配对页仍在显示后重试\n${e.chainText()}",
                e
            )
        }
        diag(context, "pair ok guid=$guid")

        // 配对刚完成，connect 服务可能还没就绪，等一下再连
        Thread.sleep(2000)

        // 2. pm grant（标准路径）。connect 端口每轮都重新 mDNS 发现，
        //    自动跳过 NsdManager 的陈旧记录。
        val grantRun = runShellWithRetry(context, identity) {
            "pm grant ${context.packageName} $PERM_WRITE_SECURE_SETTINGS"
        }
        if (!grantRun.connected) {
            diag(context, "pm grant connect failed:\n${grantRun.attempts.joinToString("\n")}")
            throw AuthException(
                "配对成功，但无法连接无线调试服务。请保持「无线调试」开启后重试。\n\n" +
                grantRun.attempts.joinToString("\n\n"),
                null
            )
        }

        val grantOut = grantRun.output.trim()
        diag(context, "pm grant raw output(${grantOut.length}): ${grantOut.ifEmpty { "<empty>" }}")
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
                diag(context, "success mode=pm_grant")
                return GrantResult(guid, shellDirect = false)
            }
            // 输出为空但权限没落下：落到兜底再试一次
            Log.w(TAG, "pm grant 无输出但权限未生效，转 shell 直写兜底")
        }

        // 3b. 兜底：很多国产 ROM（MIUI/HyperOS/ColorOS 等）只拦截 pm grant
        //     （shell 无 GRANT_RUNTIME_PERMISSIONS），但 shell 自身仍持有
        //     WRITE_SECURE_SETTINGS，可以直接 settings put secure 开启无障碍——
        //     Shizuku 生态的免 root 工具普遍用这个通道。
        val directResult = try {
            shellDirectEnableAccessibility(context, identity)
        } catch (e: Exception) {
            Log.w(TAG, "shell 直写兜底失败", e)
            DirectResult(false, "connect_error", e.chainText())
        }
        if (directResult.ok) {
            saveResult(context, guid, "shell_direct")
            Log.i(TAG, "ADB 授权完成（shell 直写兼容模式）guid=$guid")
            diag(context, "success mode=shell_direct\n${directResult.detail}")
            return GrantResult(guid, shellDirect = true)
        }

        // 4. 两条路都失败：结合 pm grant 输出与 shell 直写的真实失败阶段给提示
        Log.w(TAG, "授权失败，pm grant 输出：$grantOut；shell 直写 stage=${directResult.stage} detail=${directResult.detail}")
        diag(
            context,
            "FAIL pm_grant_out=${grantOut.ifEmpty { "<empty>" }}\n" +
            "shell_direct stage=${directResult.stage}\n${directResult.detail}"
        )
        val romSwitchBlocked =
            grantOut.contains("GRANT_RUNTIME_PERMISSIONS") || directResult.stage == "put_denied"
        val baseHint = when {
            romSwitchBlocked -> buildRomSecurityHint(grantOut.ifEmpty { directResult.detail })
            directResult.stage == "verify_failed" ->
                "配对成功，授权命令也已执行，但系统没有生效（名单被回滚）。\n" +
                "这通常被「手机管家 / 安全中心」的权限保护拦截：\n" +
                "· 重启手机后，先打开一次本应用再重试配对\n" +
                "· 小米机型可在手机管家中关闭「应用行为记录/安全扫描」类拦截后重试"
            directResult.stage == "rejected" ->
                "配对成功，但系统拒绝了 ADB 连接（配对钥匙可能已失效）。\n" +
                "请在「无线调试」页删除已配对设备，关闭再打开无线调试后重新配对。"
            directResult.stage == "no_service" ->
                "配对成功，但没有找到无线调试的连接服务。\n请保持「无线调试」页面停留在前台，再点重试。"
            grantOut.isEmpty() ->
                "授权未生效，请关闭「无线调试」后重新打开，再重新配对；仍失败请重启手机后重试"
            else -> "授权失败，请重试。\n$grantOut"
        }
        val diagTrailer =
            "\n\n技术信息 [${directResult.stage}]：${directResult.detail.take(200).ifEmpty { grantOut.take(200) }}" +
            "\n诊断日志：Android/data/${context.packageName}/files/adb_auth_diag.log"
        throw AuthException(baseHint + diagTrailer)
    }

    /**
     * 分两阶段重试，避免"找不到端口"和"认证被拒"白等几十秒：
     *  - 发现阶段：最多 2 轮 mDNS（15s + 8s 短窗）
     *  - 连接阶段：命中端口后最多连 3 次；认证/TLS 被拒立即返回（重试无意义）；
     *    TCP 类错误短窗重新发现端口（adbd 重启会换端口）
     */
    private fun runShellWithRetry(
        context: Context,
        identity: AdbKeyStore.Identity,
        command: () -> String
    ): ShellRun {
        val cmd = command()
        val attempts = mutableListOf<String>()

        // ---- 发现阶段 ----
        var target: AdbDiscovery.DiscoveredService? = null
        for (attempt in 1..2) {
            target = discoverConnect(context, if (attempt == 1) 15_000L else 8_000L)
            if (target != null) break
            Log.w(TAG, "第 $attempt/2 次未发现有效的 connect 服务")
            attempts.add(
                "第 $attempt 次：mDNS 未发现有效的 connect 服务（无记录或端口未监听）。\n" +
                "请打开「无线调试」页面停留几秒后重试"
            )
            if (attempt < 2) Thread.sleep(1500)
        }
        if (target == null) return ShellRun(connected = false, attempts = attempts)

        // ---- 连接阶段 ----
        for (attempt in 1..3) {
            val connect = target!!
            Log.i(TAG, "第 $attempt/3 次连接 adbd ${connect.host}:${connect.port}（来源:${connect.serviceName}）")
            try {
                val output = AdbConnection.shell(
                    identity, connect.host, connect.port, cmd, timeoutMs = 10_000
                )
                return ShellRun(connected = true, output = output)
            } catch (e: Exception) {
                val t = e.chainText()
                Log.w(TAG, "连接 adbd 失败 尝试 $attempt/3 ${connect.host}:${connect.port}", e)
                attempts.add("第 $attempt 次（${connect.host}:${connect.port}，来源:${connect.serviceName}）:\n$t")
                // 认证类失败：重试同一个端口结果不会变，立即返回让 UI 引导重新配对
                if (t.contains("TLS") ||
                    t.contains("配对可能未被系统接受") ||
                    t.contains("阶段3-连接被拒绝")
                ) {
                    return ShellRun(connected = false, attempts = attempts)
                }
                if (attempt < 3) {
                    Thread.sleep(1500)
                    // 端口可能已变，短窗重新定位
                    target = discoverConnect(context, 5_000L)
                }
            }
        }
        return ShellRun(connected = false, attempts = attempts)
    }

    /**
     * shell 直写开启无障碍。在 adb shell 内用一条复合命令完成"读-改-写-校验"，
     * 保留名单里其他应用的服务。返回 [DirectResult]，失败时带具体阶段与原始信息。
     * @param force true=名单中已有组件时也先移除再加回，强制系统重新绑定
     *              （开关显示开启但服务实际未运行的国产 ROM 场景）
     */
    private fun shellDirectEnableAccessibility(
        context: Context,
        identity: AdbKeyStore.Identity,
        force: Boolean = false
    ): DirectResult {
        val comp = accessibilityComponent(context)
        // 组件名只含 [a-z0-9./]，直接内联安全；其余全部用 sh 变量，避免转义问题
        val forceFlag = if (force) "1" else "0"
        // 保活豁免：利用 adb shell(uid 2000) 一次性把本应用放进系统电池/网络
        // 白名单。深度 Doze 下应用层 alarm/wakelock/WiFi 会被系统统一挂起，
        // 这是锁屏1小时左右必离线的根因，应用内任何手段都绕不过；而下列命令
        // 等价于用户在系统设置里逐层手动配置，且更彻底，重启保留。
        // 命令本身幂等，失败输出被吞掉且不影响无障碍名单写入这一主流程。
        val pkg = context.packageName
        val keepAliveExemptions = buildString {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // Doze 白名单：白名单应用在 Doze 下网络不被切断、alarm/wakelock
                // 不被推迟（等价"电池优化→不允许优化"，手动设置后白名单持久化）
                appendLine("dumpsys deviceidle whitelist +$pkg 2>/dev/null || cmd deviceidle whitelist +$pkg 2>/dev/null")
                // 放开后台运行 appops（部分 ROM 即使在 Doze 白名单也单独卡这个）
                appendLine("cmd appops set $pkg RUN_IN_BACKGROUND allow 2>/dev/null")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                appendLine("cmd appops set $pkg RUN_ANY_IN_BACKGROUND allow 2>/dev/null")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // standby 桶锁 active：最高优先级，系统不做任何作业/网络延迟
                appendLine("am set-standby-bucket $pkg active 2>/dev/null")
            }
            // 休眠时保持 WLAN 连接（MIUI 默认可能省电断 WiFi，WifiLock 被忽略时兜底）
            appendLine("settings put global wifi_sleep_policy 2 2>/dev/null")
        }.trimIndent()
        val script = """
            echo "ROM: mios=${'$'}(getprop ro.mi.os.version.name) miui=${'$'}(getprop ro.miui.ui.version.name) sdk=${'$'}(getprop ro.build.version.sdk) uid=${'$'}(id 2>/dev/null)"
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
            final="${'$'}nval"
            if [ "${'$'}found" != "1" ] || [ "${'$'}force" = "1" ]; then
              final="${'$'}{nval:+${'$'}nval:}${'$'}target"
            fi
            settings put secure accessibility_enabled 1
            if [ "${'$'}force" = "1" ] && [ "${'$'}found" = "1" ]; then
              # 组件已在名单却未绑定时：必须先提交"移除"、等系统完成解绑、
              # 再提交"加回"。若在内存拼好只写一次最终值（与原值相同），
              # Settings 观察者判定无变化，AccessibilityManagerService
              # 不会重新绑定——这正是划掉重开后 shell_ok 但服务不起的根因。
              settings put secure enabled_accessibility_services "${'$'}nval"
              sleep 1
            fi
            settings put secure enabled_accessibility_services "${'$'}final" 2>&1
            echo "PUT_EXIT=${'$'}?"
            $keepAliveExemptions
            echo $MARKER
            settings get secure enabled_accessibility_services
        """.trimIndent()

        val run = runShellWithRetry(context, identity) { script }
        if (!run.connected) {
            Log.w(TAG, "shell 直写时连接 adbd 失败：\n${run.attempts.joinToString("\n")}")
            val joined = run.attempts.joinToString("\n")
            val stage = when {
                // 三轮都没发现 connect 服务（无任何实际连接尝试）：
                // 无线调试开关可能刚打开服务未就绪，或需要停留在无线调试页
                joined.contains("未发现有效的 connect 服务") &&
                    !joined.contains("连接 adbd 失败") -> "no_service"
                // TLS 握手失败 / A_AUTH 被拒 / 配对未被接受 → 配对钥匙失效
                joined.contains("TLS") ||
                    joined.contains("配对可能未被系统接受") ||
                    joined.contains("阶段3-连接被拒绝") -> "rejected"
                else -> "connect_error"
            }
            return DirectResult(false, stage, joined.take(800))
        }
        val out = run.output
        // 某些 ROM（如澎湃 OS）拒绝时不一定吐 SecurityException，靠退出码兜底识别
        val putExitNonZero = Regex("PUT_EXIT=([1-9]\\d*|\\d{2,})").containsMatchIn(out)
        if (putExitNonZero || out.contains("SecurityException") || out.contains("Permission denial")) {
            Log.w(TAG, "shell 直写被系统拒绝：$out")
            return DirectResult(false, "put_denied", out.take(800))
        }
        val afterMarker = out.substringAfter(MARKER, "")
        val listed = afterMarker.split('\n', ':', ' ').any { it.trim() == comp }
        if (!listed) {
            return DirectResult(
                false, "verify_failed",
                "命令已执行但系统名单未包含本服务，返回：${out.take(400)}"
            )
        }

        // 系统设置落库后再用 App 侧视角确认一次（最多等 3 秒）
        if (isAccessibilityListed(context)) return DirectResult.ok()
        for (i in 1..2) {
            Thread.sleep(1500)
            if (isAccessibilityListed(context)) return DirectResult.ok()
        }
        return DirectResult(
            false, "verify_failed",
            "shell 侧名单已写入，但 App 侧连续 3 秒读不到（ROM 设置隔离/延迟）"
        )
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
            "· 小米 / 红米（澎湃OS/MIUI）：打开「USB 调试（安全设置）」（与「USB 调试」是两个开关）。" +
            "该开关需联网到小米服务器校验：必须插 SIM、关 Wi-Fi 用移动数据、并已登录小米账号；" +
            "若开关已显示开启仍报此错，请关掉它、用流量重新打开后立刻重试\n" +
            "· OPPO / 一加 / realme：USB 调试（安全设置），或关闭「权限监控」\n" +
            "· vivo / iQOO：USB 模拟点击\n" +
            "· 华为 / 荣耀：仅充电模式下允许 ADB 调试\n" +
            "· 其他品牌：在开发者选项中找「安全 / 权限 / 模拟点击」类开关"
    }

    /** 授权过程诊断落盘：filesDir/adb_auth_diag.log（用户可从
     *  Android/data/<pkg>/files/ 取出，无需 logcat）。单文件超 256KB 自动重开。 */
    private fun diag(context: Context, text: String) {
        try {
            val f = File(context.filesDir, DIAG_FILE)
            if (f.exists() && f.length() > DIAG_MAX_BYTES) runCatching { f.delete() }
            val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("===== $ts =====\n$text\n\n")
        } catch (e: Exception) {
            Log.w(TAG, "diag 落盘失败", e)
        }
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

        // 2) App 自身有 WRITE_SECURE_SETTINGS：本地写 secure settings，离线、瞬时
        if (isWriteSecureSettingsGranted(context)) {
            // 名单在但服务没起来：先移除再加回触发系统重绑；不在名单：普通追加
            val firstOk = if (listed) {
                Log.i(TAG, "enableInput：名单在但未绑定，强制重绑")
                forceRebindAccessibility(context)
            } else {
                repairAccessibility(context)
            }
            if (firstOk && awaitBound(AWAIT_BIND_MS)) {
                return EnableResult(true, "local")
            }
            // 名单写入成功但系统迟迟不绑定（实测 MIUI Android12 在 disableSelf
            // 之后普通追加只恢复开关显示）：无条件再做一次"移除→加回"强制重绑
            Log.w(TAG, "enableInput：本地写名单后 ${AWAIT_BIND_MS}ms 未绑定，强制重绑重试")
            val rebound = forceRebindAccessibility(context) && awaitBound(AWAIT_BIND_MS)
            return if (rebound) EnableResult(true, "local")
            else EnableResult(false, "local_failed")
        }

        // 3) 没本地权限：必须配对过，且此刻无线调试开着才能连 adbd
        val mode = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("grant_mode", null)
        if (mode == null) {
            return EnableResult(false, "not_paired")
        }
        // 3.1) 快速预检：无线调试总开关明确关闭时 shell 必连不上。
        //   直接秒回失败（否则要等 mDNS 扫满 15 秒，用户体感"一直打不开"），
        //   UI 收到后立刻弹清单引导用户去开发者选项重新打开无线调试。
        //   读状态异常（个别 ROM 键不可读）时不短路，走完整扫描兜底。
        if (!isWirelessDebugEnabled(context)) {
            Log.i(TAG, "enableInput：无线调试未开启，跳过 shell 重连")
            return EnableResult(false, "wireless_debug_off")
        }
        return try {
            val identity = AdbKeyStore.getOrCreate(context)
            // 名单在但没绑定 → force 强制重绑；不在名单 → 普通追加
            var r = shellDirectEnableAccessibility(context, identity, force = listed)
            if (r.ok && awaitBound(AWAIT_BIND_MS)) {
                EnableResult(true, "shell")
            } else if (r.ok) {
                // 同本地路径：名单已写入但系统不绑定，force 重写一轮强制触发重绑
                Log.w(TAG, "enableInput：shell 写名单后 ${AWAIT_BIND_MS}ms 未绑定，force 重写重试")
                r = shellDirectEnableAccessibility(context, identity, force = true)
                if (r.ok && awaitBound(AWAIT_BIND_MS)) {
                    EnableResult(true, "shell")
                } else {
                    EnableResult(false, "shell_${r.stage}", r.detail)
                }
            } else {
                // shell_no_service：开关开着但服务没广播（引导进无线调试页停留）
                // shell_rejected：配对失效（引导重新配对）
                // shell_put_denied：ROM 安全开关（引导 USB 调试安全设置）
                // shell_verify_failed / shell_connect_error：重试+技术详情
                EnableResult(false, "shell_${r.stage}", r.detail)
            }
        } catch (e: Exception) {
            Log.w(TAG, "enableInput shell 路径异常", e)
            EnableResult(false, "shell_connect_error", e.chainText())
        }
    }

    /**
     * @param ok InputService 是否已经真正被系统绑定（onServiceConnected 完成），
     *   不再是"名单写入成功"——部分国产 ROM 写名单后只恢复开关显示而不绑定
     * @param mode 成功：already/local/shell；
     *   失败原因码：not_paired / wireless_debug_off / local_failed /
     *   shell_no_service（无线调试开着但没发现连接服务）/
     *   shell_rejected（配对失效需重新配对）/ shell_put_denied（ROM 安全开关拦截）/
     *   shell_verify_failed / shell_connect_error
     * @param detail 技术详情（原始错误片段，仅排查用）
     */
    data class EnableResult(val ok: Boolean, val mode: String, val detail: String = "")

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

    /**
     * 无线调试是否真的在运行。必须在非主线程调用（本函数就在 enableInput
     * 的后台线程里）。
     *
     * 不能只靠 Settings.Global "adb_wifi"：MIUI/HyperOS 部分版本对普通
     * 应用屏蔽该键，读异常时代码若保守返回 true 就会放行到后面的长扫描，
     * 用户干等几十秒还看不到"请打开无线调试"的引导。新逻辑：
     *  - 键可读且值明确：按值返回（毫秒级）
     *  - 键读不到（null/异常）：直接做 3.5s mDNS 实测——服务在广播才算开。
     *    探测命中会记住端口，后续 shell 重连直接复用，总耗时反而更短
     */
    private fun isWirelessDebugEnabled(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        try {
            val v = Settings.Global.getString(context.contentResolver, "adb_wifi")
            if (v == "1") return true
            if (v == "0") return false
            Log.i(TAG, "adb_wifi 键不可读（v=$v），改用 mDNS 实测无线调试状态")
        } catch (e: Exception) {
            Log.w(TAG, "读取 adb_wifi 失败，改用 mDNS 实测", e)
        }
        return AdbDiscovery.quickProbeConnectPort(context) != null
    }

    private fun discoverConnect(context: Context, scanTimeoutMs: Long = 15_000L): AdbDiscovery.DiscoveredService? {
        // 0) 先探活上次成功用过的端口：部分 ROM 重开无线调试会复用同一端口，
        //    命中可跳过 NsdManager 冷启动的数秒延迟（不命中代价仅一次 bind）。
        //    端口已持久化：App 被划掉重开、无线调试未重启时直接命中，无需 mDNS
        AdbDiscovery.cachedPort(context)?.let { p ->
            if (AdbDiscovery.isLoopbackPortListening(p)) {
                Log.i(TAG, "discoverConnect：命中缓存端口 :$p，直接使用")
                return AdbDiscovery.DiscoveredService("cached", "127.0.0.1", p, emptyMap())
            }
        }
        // 只从 mDNS 获取端口，host 强制 127.0.0.1（本机连本机回环最可靠）。
        // findFirst 已做"本机网卡地址 + 127.0.0.1 端口存活"双重校验，自动跳过陈旧记录。
        // 单个连续发现窗口（不反复 stop/restart discovery）：
        // NsdManager 冷启动本身就要几秒，连续窗口比"多次短窗重启扫描"命中率高。
        val s = AdbDiscovery.findFirst(context, AdbDiscovery.TYPE_CONNECT, timeoutMs = scanTimeoutMs)
        if (s != null) {
            AdbDiscovery.rememberConnectPort(context, s.port)
            return AdbDiscovery.DiscoveredService(s.serviceName, "127.0.0.1", s.port, s.attributes)
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
