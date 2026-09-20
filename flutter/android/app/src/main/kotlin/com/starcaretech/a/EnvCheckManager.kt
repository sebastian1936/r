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

    /** 环境检查完整返回：品牌信息 + 检查项（清单按品牌只下发相关项） */
    fun envInfo(context: Context): Map<String, Any?> = mapOf(
        "brand" to brandKey,
        "brand_label" to brandLabel,
        "items" to checkAll(context)
    )

    fun checkAll(context: Context): List<Map<String, String>> {
        val items = ArrayList<Item>()
        items += Item("developer_options", globalStatus(context, "development_settings_enabled"))
        items += Item("adb_master", globalStatus(context, Settings.Global.ADB_ENABLED))
        // ADB_WIFI_ENABLED（隐藏常量，值 "adb_wifi"，API30+）
        items += Item(
            "wireless_debug",
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                globalStatus(context, "adb_wifi")
            } else {
                STATUS_UNKNOWN
            }
        )
        items += Item("notification", checkNotification(context))
        items += Item("overlay", checkOverlay(context))
        items += Item("battery_optimization", checkBattery(context))
        if (isMiui) {
            items += Item("miui_autostart", appOpStatus(context, MIUI_OP_AUTO_START))
            items += Item(
                "miui_background_start",
                miuiBackgroundStartStatus(context)
            )
            // 配对码通过通知 RemoteInput 输入（与 Shizuku 同款），MIUI 默认
            // 通知栏样式会吞掉通知上的输入控件。该样式设置无任何 API 可读，
            // 恒为 unknown，强制用户按指引手动确认（小米/红米配对硬前提）。
            items += Item("miui_notif_style", STATUS_UNKNOWN)
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

    private fun checkNotification(context: Context): String {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (!nm.areNotificationsEnabled()) return STATUS_OFF
        // 配对渠道若被手动关到 IMPORTANCE_NONE，配对通知同样不显示
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
            "developer_options", "adb_master", "wireless_debug" ->
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

            // One UI「后台使用限制/休眠应用」页无公开 intent，
            // 打开电池优化设置页（含本应用电池"不受限制"开关），再按文案操作
            "samsung_sleep_apps" -> openBatterySettings(appContext)

            else -> openAppDetails(appContext)
        }
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
