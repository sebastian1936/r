package com.starcaretech.a

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.util.Log
import com.starcaretech.a.adb.AdbPairingService

/**
 * 「一键配对/保活」前置环境检查：把系统里分散的开关一次性查清楚，
 * 没开的由 Dart 侧清单引导跳转对应设置页。
 *
 * 可检测性说明（Android 生态限制）：
 *  - 开发者模式 / USB 调试总开关 / 无线调试：Settings.Global 可读
 *  - 悬浮窗 / 电池优化白名单 / 通知：公开 API
 *  - MIUI「自启动」「后台弹出界面」：无公开 API，只能反射 AppOps 尽力探测，
 *    探测不到时返回 unknown，UI 仍展示并允许用户手动确认
 *  - 「最近任务加锁」「锁屏显示」：完全无法检测，UI 做静态提示
 */
object EnvCheckManager {
    private const val TAG = "EnvCheck"

    const val STATUS_OK = "ok"
    const val STATUS_OFF = "off"
    const val STATUS_UNKNOWN = "unknown"

    // MIUI AppOps 操作码（非 SDK，反射调用；不存在/异常一律 unknown）
    private const val MIUI_OP_AUTO_START = 10008
    // 「后台弹出界面」实测：MIUI11~HyperOS 主流版本 op=10021
    // （OP_BACKGROUND_START_ACTIVITY，小米框架字段实锤）；早期部分代码
    // 流传 10022。两个码都探，任一 ALLOWED 即判定已开，避免错码导致
    // "开关已开却报关闭"（10022 在部分机型是另一个 op，默认 IGNORED）
    private const val MIUI_OP_BACKGROUND_START_ACTIVITY_V1 = 10021
    private const val MIUI_OP_BACKGROUND_START_ACTIVITY_V2 = 10022

    data class Item(val key: String, val status: String)

    /** 品牌识别结果：key 供逻辑判断，label 直接展示给用户 */
    val brandKey: String by lazy {
        when {
            isMiui -> "xiaomi"
            isSamsung -> "samsung"
            com.starcaretech.a.adb.HarmonyOsDetector.isHarmonyOs -> "huawei"
            else -> "other"
        }
    }

    val brandLabel: String by lazy {
        when (brandKey) {
            "xiaomi" -> "小米 / 红米（MIUI / HyperOS）"
            "samsung" -> "三星（One UI）"
            "huawei" -> "华为 / 荣耀（鸿蒙）"
            else -> (Build.MANUFACTURER ?: "其他") + "（标准安卓）"
        }
    }

    /**
     * 环境检查完整返回：品牌信息 + 检查项（清单按品牌/机型只下发相关项）
     *
     * @param pairingCapable 机型是否支持无线调试配对（Android11+ 且非鸿蒙）。
     *   支持的机型——即使是已配对后的保活复查——也下发开发者模式/USB调试/
     *   无线调试/MIUI通知样式：因为无线调试会在关 WiFi 等场景被系统关闭，
     *   复查时必须让用户看到并重新打开；不支持的机型（Android10以下/鸿蒙）
     *   任何场景都不下发这些项。
     * @param checkPairingChannel 是否检查"配对专用通知渠道"。
     *   仅配对前/开启前重试需要；保活复查只查通知总开关，避免已配对用户
     *   被无关的渠道状态干扰。
     */
    fun envInfo(
        context: Context,
        pairingCapable: Boolean,
        checkPairingChannel: Boolean
    ): Map<String, Any?> = mapOf(
        "brand" to brandKey,
        "brand_label" to brandLabel,
        "items" to checkAll(context, pairingCapable, checkPairingChannel)
    )

    fun checkAll(
        context: Context,
        pairingCapable: Boolean = true,
        checkPairingChannel: Boolean = true
    ): List<Map<String, String>> {
        val items = ArrayList<Item>()
        if (pairingCapable) {
            items += Item("developer_options", globalStatus(context, "development_settings_enabled"))
            items += Item("adb_master", globalStatus(context, Settings.Global.ADB_ENABLED))
            // ADB_WIFI_ENABLED（隐藏常量，值 "adb_wifi"，API30+）。
            // MIUI/HyperOS 部分版本对普通应用屏蔽该键（读到 null/异常），
            // 此时不靠猜：直接探测 adbd 有没有在广播无线调试服务，
            // 探测到=确实开着，探测不到=没开，清单不再显示无意义的问号。
            // 注意：含 mDNS 短窗（最多 3.5s），调用方必须在非主线程。
            items += Item(
                "wireless_debug",
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    wirelessDebugStatus(context)
                } else {
                    STATUS_UNKNOWN
                }
            )
        }
        items += Item(
            "notification",
            checkNotification(context, checkPairingChannel = checkPairingChannel)
        )
        items += Item("overlay", checkOverlay(context))
        items += Item("battery_optimization", checkBattery(context))
        // 以下两项是功能可选项：不授权不影响远程看屏与操作，
        // 只影响对应能力（传文件 / 传被控端播放的声音）
        items += Item("file_storage", checkFileStorage(context))
        // 被控端系统音频仅 Android 11+ 支持，低版本无此项
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            items += Item("record_audio", checkRecordAudio(context))
        }
        if (isMiui) {
            items += Item("miui_autostart", appOpStatus(context, MIUI_OP_AUTO_START))
            items += Item(
                "miui_background_start",
                miuiBackgroundStartStatus(context)
            )
            // 配对码通过通知 RemoteInput 输入（与 Shizuku 同款），MIUI 默认
            // 通知栏样式会吞掉通知上的输入控件。仅配对机型下发（配对前提，
            // 但配对后若被改回默认样式，重新配对/恢复时同样需要它）。
            if (pairingCapable) {
                items += Item("miui_notif_style", STATUS_UNKNOWN)
            }
            // MIUI/HyperOS 开发者选项里的「直接进入系统」：无锁屏密码时打开它，
            // 远程唤醒后不用在锁屏页上滑即可直接进桌面。状态无 API 可读，
            // 恒 unknown；属于可选项（设了锁屏密码时该开关灰显，无法开启）。
            items += Item("miui_direct_boot", STATUS_UNKNOWN)
            // MIUI 私有的应用省电策略（旧称神隐模式）独立于安卓 Doze，
            // adb 也无法改写其配置：必须用户手动选「无限制」，否则锁屏后
            // 系统会断网休眠，信令必断。恒 unknown，作为必看项展示。
            items += Item("miui_battery_unrestricted", STATUS_UNKNOWN)
        }
        if (isSamsung) {
            // One UI 无线调试/通知输入/无障碍均为标准实现，无配对相关特殊项；
            // 唯一实际差异是电池策略：长期不打开的应用会被自动放入深度休眠，
            // 服务与看门狗全部失效。该状态无公开 API 可读，恒 unknown，
            // 作为保活建议项由用户手动确认。
            items += Item("samsung_sleep_apps", STATUS_UNKNOWN)
        }
        return items.map { mapOf("key" to it.key, "status" to it.status) }
    }

    private fun globalStatus(context: Context, name: String): String = try {
        when (Settings.Global.getInt(context.contentResolver, name, -1)) {
            1 -> STATUS_OK
            0 -> STATUS_OFF
            else -> STATUS_UNKNOWN
        }
    } catch (e: Throwable) {
        Log.w(TAG, "read global setting fail: $name", e)
        STATUS_UNKNOWN
    }

    /**
     * 无线调试真实状态。必须在非主线程调用（读不到设置键时会做最多
     * 3.5 秒的 mDNS 服务探测）：
     *  - 设置键可读且值明确：直接按值返回（最快）
     *  - 键被 ROM 屏蔽（null/异常，MIUI 常见）：探测系统是否真的在
     *    广播 _adb-tls-connect 服务——无线调试开着时该服务必然存在。
     *    探测结果同时缓存端口给恢复流程复用。
     */
    private fun wirelessDebugStatus(context: Context): String {
        try {
            val v = Settings.Global.getString(context.contentResolver, "adb_wifi")
            if (v == "1") return STATUS_OK
            if (v == "0") return STATUS_OFF
            // v == null：键不存在或被屏蔽，落到探测
        } catch (e: Throwable) {
            Log.w(TAG, "read adb_wifi fail, fallback to mDNS probe", e)
        }
        val port = try {
            com.starcaretech.a.adb.AdbDiscovery.quickProbeConnectPort(context)
        } catch (e: Throwable) {
            Log.w(TAG, "probe wireless debug service fail", e)
            null
        }
        Log.i(TAG, "adb_wifi 不可读，mDNS 探测结果：${port ?: "未发现服务"}")
        return if (port != null) STATUS_OK else STATUS_OFF
    }

    private fun checkNotification(
        context: Context,
        checkPairingChannel: Boolean
    ): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.areNotificationsEnabled()) return STATUS_OFF
        // 配对渠道若被手动关到 IMPORTANCE_NONE，配对通知同样不显示
        // （纯配对前提，保活复查不查这个渠道）
        if (checkPairingChannel &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        ) {
            nm.getNotificationChannel(AdbPairingService.CHANNEL_ID)?.let { ch ->
                if (ch.importance == android.app.NotificationManager.IMPORTANCE_NONE) {
                    return STATUS_OFF
                }
            }
        }
        return STATUS_OK
    }

    private fun checkOverlay(context: Context): String =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)) {
            STATUS_OK
        } else {
            STATUS_OFF
        }

    private fun checkBattery(context: Context): String {
        val pm = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
        return if (pm.isIgnoringBatteryOptimizations(context.packageName)) STATUS_OK else STATUS_OFF
    }

    /** 传文件权限：Android11+ 为"所有文件访问"，低版本为运行时存储权限 */
    private fun checkFileStorage(context: Context): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return if (android.os.Environment.isExternalStorageManager()) STATUS_OK else STATUS_OFF
        }
        return if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) STATUS_OK else STATUS_OFF
    }

    /**
     * 远程声音权限：即 RECORD_AUDIO。安卓规定任何音频采集（含系统内部
     * 播放声）都必须有该权限，没有单独的"内部声音"授权
     */
    private fun checkRecordAudio(context: Context): String =
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        ) STATUS_OK else STATUS_OFF

    /**
     * 「后台弹出界面」跨 MIUI 版本探测：同时查 10021/10022，
     * 任一 ALLOWED→已开；都不是允许但有明确拒绝→关闭；否则 unknown
     */
    private fun miuiBackgroundStartStatus(context: Context): String {
        val modes = listOf(
            MIUI_OP_BACKGROUND_START_ACTIVITY_V1,
            MIUI_OP_BACKGROUND_START_ACTIVITY_V2
        ).map { probeAppOpMode(context, it) }
        Log.i(TAG, "miui background-start modes=$modes")
        if (modes.any { it != null && it == AppOpsManager.MODE_ALLOWED }) {
            return STATUS_OK
        }
        val known = modes.filterNotNull()
        return if (known.isNotEmpty() &&
            known.all { it == AppOpsManager.MODE_IGNORED || it == AppOpsManager.MODE_ERRORED }
        ) {
            STATUS_OFF
        } else {
            STATUS_UNKNOWN
        }
    }

    /**
     * MIUI AppOps 反射探测。模式值：0=ALLOWED 1=IGNORED 2=ERRORED 3=DEFAULT 等。
     * 只有明确允许/拒绝才下结论，其余（含 ROM 无此 op、默认值）返回 unknown。
     *
     * 关键坑（HyperOS/Android 13+ 实测）：非公开 op 普通应用调
     * checkOpNoThrow 会被框架直接回 MODE_IGNORED（与开关实际状态无关）。
     * Android 10 起优先用 unsafeCheckOpNoThrow（不校验调用方查询权限），
     * 低版本回退旧方法。查不到（方法/ROM 异常）返回 null。
     */
    private fun appOpStatus(context: Context, op: Int): String =
        when (probeAppOpMode(context, op)) {
            AppOpsManager.MODE_ALLOWED -> STATUS_OK
            AppOpsManager.MODE_IGNORED, AppOpsManager.MODE_ERRORED -> STATUS_OFF
            else -> STATUS_UNKNOWN
        }

    private fun probeAppOpMode(context: Context, op: Int): Int? = try {
        val aom = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        try {
            val m = AppOpsManager::class.java.getMethod(
                "unsafeCheckOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            m.invoke(aom, op, Process.myUid(), context.packageName) as Int
        } catch (e: NoSuchMethodException) {
            val m = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            m.invoke(aom, op, Process.myUid(), context.packageName) as Int
        }
    } catch (e: Throwable) {
        Log.w(TAG, "appop probe fail op=$op", e)
        null
    }

    val isMiui: Boolean by lazy {
        runCatching {
            val clz = Class.forName("android.os.SystemProperties")
            val get = clz.getMethod("get", String::class.java)
            !(get.invoke(null, "ro.miui.ui.version.name") as? String).isNullOrEmpty()
        }.getOrDefault(false) ||
            runCatching {
                Build.MANUFACTURER?.lowercase()?.contains("xiaomi") == true ||
                    Build.BRAND?.lowercase()?.contains("redmi") == true
            }.getOrDefault(false)
    }

    val isSamsung: Boolean by lazy {
        Build.MANUFACTURER?.equals("samsung", ignoreCase = true) == true
    }

    /**
     * 打开各检查项对应的设置页。返回 true 表示成功拉起了某个页面。
     * 尽量直达；ROM 页面缺失时逐级回退，最终回退应用详情页。
     */
    fun openSetting(context: Context, key: String): Boolean {
        val appContext = context.applicationContext
        return when (key) {
            // 无线调试：优先直达系统「无线调试」子页（少点一层），
            // 组件不存在/未导出（部分 ROM）时回退开发者选项主页
            "wireless_debug" -> openWirelessDebugSettings(appContext)

            "developer_options", "adb_master" ->
                launch(appContext, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

            "notification" -> openNotificationSettings(appContext)

            "overlay" ->
                launch(
                    appContext,
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${appContext.packageName}")
                    )
                )

            "battery_optimization" -> openBatterySettings(appContext)

            "miui_autostart" -> openMiuiAutoStart(appContext)

            "miui_background_start" -> openMiuiPermissionEditor(appContext)

            // MIUI「通知栏样式」页无稳定公开 intent，先打开系统设置主页，
            // 用户按文案路径进入（通知与控制中心 → 通知通知栏 → 通知栏样式）
            "miui_notif_style" ->
                launch(appContext, Intent(Settings.ACTION_SETTINGS))

            // MIUI「直接进入系统」在开发者选项页内，无独立 intent
            "miui_direct_boot" ->
                launch(appContext, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))

            "miui_battery_unrestricted" -> openMiuiBatterySettings(appContext)

            // One UI「后台使用限制/休眠应用」页无公开 intent，
            // 打开电池优化设置页（含本应用电池"不受限制"开关），再按文案操作
            "samsung_sleep_apps" -> openBatterySettings(appContext)

            else -> openAppDetails(appContext)
        }
    }

    /**
     * 直达「无线调试」子页面。系统未暴露公开 Intent action，只能按
     * AOSP/各 ROM 常见组件名尝试；组件未导出/不存在会抛异常，被
     * [launch] 兜住返回 false，最终回退开发者选项主页，行为不比现在差。
     */
    private fun openWirelessDebugSettings(context: Context): Boolean {
        val candidates = mutableListOf<Intent>()
        // AOSP 设置里无线调试页的内部 Activity（Pixel/原生/部分 MIUI 保留同名组件）
        runCatching {
            candidates += Intent().setComponent(
                ComponentName(
                    "com.android.settings",
                    "com.android.settings.Settings\$WifiDebugActivity"
                )
            )
        }
        // AOSP activity-alias 形式（无 Settings$ 前缀）
        runCatching {
            candidates += Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.WifiDebugActivity")
            )
        }
        // 部分 ROM 暴露的 action 别名
        candidates += Intent("android.settings.WIFI_DEBUGGING_SETTINGS")
        candidates.forEach { if (launch(context, it)) return true }
        return launch(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    }

    private fun openNotificationSettings(context: Context): Boolean {
        // 8.0+ 直达本应用通知设置；低版本回退应用详情
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            if (launch(context, intent)) return true
        }
        return openAppDetails(context)
    }

    private fun openBatterySettings(context: Context): Boolean {
        // 先试系统弹窗式的「不优化电池」请求（多数 ROM 直达开关/本应用电池页）
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}")
        )
        if (launch(context, direct)) return true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (launch(context, Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))) return true
        }
        return openAppDetails(context)
    }

    private fun openMiuiAutoStart(context: Context): Boolean {
        val candidates = listOf(
            Intent().setComponent(
                ComponentName(
                    "com.miui.securitycenter",
                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
                )
            ),
            Intent("miui.intent.action.OP_AUTO_START")
                .addCategory(Intent.CATEGORY_DEFAULT)
        )
        candidates.forEach { if (launch(context, it)) return true }
        return openAppDetails(context)
    }

    /**
     * MIUI/HyperOS 单应用「省电策略」页（无限制/省电推荐/无限制后台）。
     * 该页是 powerkeeper 私有 Activity，不同 MIUI 代际组件名不同，逐个尝试，
     * 全部失败时回退系统"不优化电池"弹窗，再回退应用详情页
     * （详情页内也有「省电策略」入口）。
     */
    private fun openMiuiBatterySettings(context: Context): Boolean {
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        }.getOrDefault(context.packageName)
        val candidates = listOf(
            // 旧版/MIUI 经典入口：神隐模式单应用配置页
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"
                )
            ).putExtra("package_name", context.packageName)
                .putExtra("package_label", label),
            // MIUI 12/13 部分版本的应用电池详情页
            Intent().setComponent(
                ComponentName(
                    "com.miui.powerkeeper",
                    "com.miui.powerkeeper.ui.apps.detail.AppDetailActivity"
                )
            ).putExtra("package_name", context.packageName)
                .putExtra("package_label", label)
        )
        candidates.forEach { if (launch(context, it)) return true }
        return openBatterySettings(context)
    }

    private fun openMiuiPermissionEditor(context: Context): Boolean {
        val candidates = listOf(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.PermissionsEditorActivity"
            ),
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
            )
        ).map { cn ->
            Intent()
                .setComponent(cn)
                .putExtra("extra_pkgname", context.packageName)
        }
        candidates.forEach { if (launch(context, it)) return true }
        return openAppDetails(context)
    }

    private fun openAppDetails(context: Context): Boolean = launch(
        context,
        Intent(
            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:${context.packageName}")
        )
    )

    /**
     * 不做 resolveActivity 预判：targetSdk 30+ 包可见性限制下，
     * 安全中心等系统页查不到但能正常拉起。直接 startActivity，失败返回 false。
     */
    private fun launch(context: Context, intent: Intent): Boolean = try {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (e: Throwable) {
        Log.w(TAG, "startActivity fail: ${intent.component ?: intent.action}", e)
        false
    }
}
