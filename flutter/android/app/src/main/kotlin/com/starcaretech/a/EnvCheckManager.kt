package com.starcaretech.a

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
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
 *  - 华为（EMUI/鸿蒙）的应用启动管理、耗电详情后台活动、省电策略，
 *    以及各品牌「最近任务加锁」：完全无法检测，恒 unknown；
 *    前三项尽力跳转华为手机管家私有页，最近任务加锁无设置页只做文案指引
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
            isHuawei -> "huawei"
            isHonor -> "honor"
            isOppo -> "oppo"
            isVivo -> "vivo"
            else -> "other"
        }
    }

    val brandLabel: String by lazy {
        when (brandKey) {
            "xiaomi" -> "小米 / 红米（MIUI / HyperOS）"
            "samsung" -> "三星（One UI）"
            "huawei" -> "华为（鸿蒙 / EMUI）"
            "honor" -> "荣耀（MagicOS）"
            "oppo" -> "OPPO / 一加 / realme（ColorOS）"
            "vivo" -> "vivo / iQOO（OriginOS）"
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
     * @param keepAliveOnly 手动授权模式开启前的保活检查：只下发保活相关项
     *   （通知/电池/悬浮窗/自启动/后台弹出/三星防休眠），不含任何无线调试
     *   相关项，也不含文件/录音功能权限项。
     */
    fun envInfo(
        context: Context,
        pairingCapable: Boolean,
        checkPairingChannel: Boolean,
        keepAliveOnly: Boolean = false
    ): Map<String, Any?> = mapOf(
        "brand" to brandKey,
        "brand_label" to brandLabel,
        "items" to checkAll(
            context,
            pairingCapable && !keepAliveOnly,
            checkPairingChannel && !keepAliveOnly,
            keepAliveOnly
        )
    )

    fun checkAll(
        context: Context,
        pairingCapable: Boolean = true,
        checkPairingChannel: Boolean = true,
        keepAliveOnly: Boolean = false
    ): List<Map<String, String>> {
        val items = ArrayList<Item>()
        if (pairingCapable) {
            items += Item("developer_options", globalStatus(context, "development_settings_enabled"))
            items += Item("adb_master", globalStatus(context, Settings.Global.ADB_ENABLED))
            // 小米/红米专有：「USB 调试（安全设置）」是与 USB 调试总开关
            // 独立的第二个开关，控制 adb shell 的 pm grant/写 secure settings
            // 权限——不开它无线配对会成功但授权失败。该开关状态无任何 API
            // 可读（Settings/provider/AppOps 都不暴露），恒 unknown 由用户
            // 手动确认；开启还需小米服务器在线校验（插 SIM+移动数据+账号）。
            if (isMiui) {
                items += Item("miui_adb_secure", STATUS_UNKNOWN)
            }
            // vivo / iQOO：对应开关为「USB 模拟点击」
            if (isVivo) {
                items += Item("vivo_usb_simulate", STATUS_UNKNOWN)
            }
            // OPPO / 一加 / realme（ColorOS）：对应开关为「禁止权限监控」，
            // 在开发者选项列表最底部（部分固件名「禁用系统优化」，个别固件
            // 中文界面隐藏需切英文开启）。该开关状态无任何 API 可读
            // （Settings/provider/AppOps 都不暴露），恒 unknown 由用户手动确认
            if (isOppo) {
                items += Item("oppo_permission_monitor", STATUS_UNKNOWN)
            }
            // 无线调试必须在已连接 Wi-Fi 时才能打开/保持运行，放在它前面
            // 先排查（系统在未连 Wi-Fi 时会直接灰掉或自动关闭无线调试）
            items += Item("wifi_connected", checkWifi(context))
            // ADB_WIFI_ENABLED（隐藏常量，值 "adb_wifi"，API30+）。
            // MIUI/OriginOS 部分版本对普通应用屏蔽该键（读到 null/异常），
            // 此时不靠猜：直接探测 adbd 有没有在广播无线调试服务，
            // 探测到=确实开着；键不可读且探测不到时给 unknown（不拦配对），
            // 因为部分 ROM 的 mDNS 栈对 connect 服务 query 响应极差，
            // 探不到不等于没开。注意：含两轮 mDNS（最多约 7s），必须非主线程。
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
        // 电池「无限制/不受限制」不是小米专有：Android 6+ 全系都有
        // Doze 电池优化白名单（isIgnoringBatteryOptimizations 为通用 API），
        // 只是 MIUI 的设置入口和文案不同——小米下发 MIUI 专属项直达私有页，
        // 其余品牌下发通用项。两者是同一个操作的两种引导，不重复下发。
        // 该项是被控端长期在线的前提，位置上移到必选区域。
        items += Item(
            if (isMiui) "miui_battery_unrestricted" else "battery_optimization",
            checkBattery(context)
        )
        items += Item("overlay", checkOverlay(context))
        // 以下两项是功能可选项：不授权不影响远程看屏与操作，
        // 只影响对应能力（传文件 / 传被控端播放的声音）。
        // 手动授权的保活检查只关注保活项，不下发功能权限项。
        if (!keepAliveOnly) {
            items += Item("file_storage", checkFileStorage(context))
            // 被控端系统音频仅 Android 11+ 支持，低版本无此项
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                items += Item("record_audio", checkRecordAudio(context))
            }
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
        }
        if (isSamsung) {
            // One UI 无线调试/通知输入/无障碍均为标准实现，无配对相关特殊项；
            // 唯一实际差异是电池策略：长期不打开的应用会被自动放入深度休眠，
            // 服务与看门狗全部失效。该状态无公开 API 可读，恒 unknown，
            // 作为保活建议项由用户手动确认。
            items += Item("samsung_sleep_apps", STATUS_UNKNOWN)
        }
        if (isVivo) {
            // vivo/iQOO（OriginOS）保活四件套。实测 i管家/电池引擎
            // （com.vivo.abe）会对前台服务执行 force-stop 级强杀，杀后
            // 应用进入 stopped 状态，AlarmManager/JobScheduler/无障碍/
            // 开机广播全部撤销，看门狗无法自救，只能人工点开 App。
            // 通用 Doze 白名单（battery_optimization）挡不住该强杀，
            // 必须配好 vivo 私有的自启动/后台高耗电名单等。四项均无
            // 任何 API 可读，恒 unknown；前三项按已知私有组件名尽力跳转
            // 设置页（见 openSetting），最近任务加锁无设置页只能文案指引；
            // 均不阻塞流程。
            items += Item("vivo_autostart", STATUS_UNKNOWN)
            items += Item("vivo_background_power", STATUS_UNKNOWN)
            items += Item("vivo_recent_lock", STATUS_UNKNOWN)
            items += Item("vivo_power_save", STATUS_UNKNOWN)
        }
        if (isHuawei) {
            // 华为（EMUI / 鸿蒙）后台限制比原生 Doze 更严：即使开了
            // 「电池不受限制」（battery_optimization），EMUI 的「应用启动
            // 管理」仍会禁止自启动/后台活动，「耗电详情」也会限制后台
            // 运行。Android 10 及以下无系统级无线调试，无法用 ADB shell
            // 写入 Doze 白名单，全靠用户手动配好这几项。四项均无 API
            // 可读，恒 unknown；前三项尽力跳转华为手机管家私有页，最近
            // 任务加锁无设置页只能文案指引；均不阻塞流程。
            items += Item("huawei_autostart", STATUS_UNKNOWN)
            items += Item("huawei_background_power", STATUS_UNKNOWN)
            items += Item("huawei_recent_lock", STATUS_UNKNOWN)
            items += Item("huawei_power_save", STATUS_UNKNOWN)
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
     * 无线调试真实状态。必须在非主线程调用（读不到设置键时会做两轮
     * mDNS 服务探测，最多约 7 秒）：
     *  - 设置键可读且值明确：直接按值返回（最快）
     *  - 键被 ROM 屏蔽（null/异常，MIUI/OriginOS 均有实测）：探测系统是否
     *    真的在广播 _adb-tls-connect 服务——无线调试开着时该服务必然存在。
     *    首轮冷启动可能整窗收不到响应（部分 ROM mdns 栈对 connect 服务
     *    的 query 响应很差），重开一轮独立发现再确认。
     *  - 键不可读且两轮探测均无服务：返回 UNKNOWN 而非 OFF——mDNS 收不到
     *    不等于开关没开（误报会硬拦配对入口），UNKNOWN 在清单里不阻塞
     *    "开始配对"；用户真没开时，配对阶段扫 pairing 服务还会再拦一次。
     *  - 实测探测代价高（4s+3s NsdManager），结果走 [probeWirelessDebugCached]
     *    的短 TTL 缓存，Dart 高频刷新清单时不重复探测。
     */
    private fun wirelessDebugStatus(context: Context): String {
        var raw: String? = null
        var readThrew = false
        try {
            raw = Settings.Global.getString(context.contentResolver, "adb_wifi")
            if (raw == "1") return STATUS_OK
            if (raw == "0") return STATUS_OFF
            // raw == null/空：键不存在或被屏蔽，落到探测
        } catch (e: Throwable) {
            readThrew = true
            Log.w(TAG, "read adb_wifi fail, fallback to mDNS probe", e)
        }
        return probeWirelessDebugCached(context, raw, readThrew)
    }

    // mDNS 实测无线调试状态代价很高（4s+3s NsdManager 发现）。Dart 端每次
    // 页面可见/生命周期回调都会拉一次 envInfo，实测日志中三星 1s 内连探 3 次、
    // 小米 1 分钟内探 15 次：费电，且多个 NsdManager 发现并发会互相丢响应。
    // 探测结果按短 TTL 缓存；TTL 窗口内的并发调用在同一把锁上合并为一次探测
    //（后来的线程等首个线程探完直接读结果，即 single-flight）。仅用于引导
    // 清单展示；enableInput 前的实时预检走 AdbAuthManager.isWirelessDebugEnabled，
    // 不经过此缓存，用户刚打开无线调试不会被旧结果挡住。
    private const val WIRELESS_PROBE_TTL_MS = 8_000L
    private val wirelessProbeLock = Any()

    private class WirelessProbe(val status: String, val atMs: Long)

    @Volatile
    private var wirelessProbeCache: WirelessProbe? = null

    /**
     * 带 TTL 缓存 + single-flight 的 mDNS 实测。只可能返回
     * [STATUS_OK]/[STATUS_UNKNOWN]（设置键明确可读的快路径在调用处处理）。
     */
    private fun probeWirelessDebugCached(
        context: Context,
        raw: String?,
        readThrew: Boolean
    ): String {
        SystemClock.elapsedRealtime().let { now ->
            wirelessProbeCache?.let {
                if (now - it.atMs < WIRELESS_PROBE_TTL_MS) {
                    Log.i(TAG, "wireless_debug mDNS 探测命中 ${WIRELESS_PROBE_TTL_MS}ms 缓存：${it.status}")
                    return it.status
                }
            }
        }
        // 整个探测放在锁内：并发调用排队，第二个进来时缓存已写好、双检直接命中，
        // 保证同一时刻最多只有一个 NsdManager 发现在跑（阻塞的调用本就在后台线程）。
        synchronized(wirelessProbeLock) {
            wirelessProbeCache?.let {
                if (SystemClock.elapsedRealtime() - it.atMs < WIRELESS_PROBE_TTL_MS) {
                    return it.status
                }
            }
            val port = try {
                com.starcaretech.a.adb.AdbDiscovery.quickProbeConnectPort(
                    context, timeoutMs = 4_000L, extraRoundMs = 3_000L
                )
            } catch (e: Throwable) {
                Log.w(TAG, "probe wireless debug service fail", e)
                null
            }
            Log.i(TAG, "adb_wifi 不可读(raw=$raw threw=$readThrew)，mDNS 探测结果：${port ?: "未发现服务"}")
            com.starcaretech.a.adb.AdbAuthManager.trace(
                context,
                "envCheck wireless_debug adb_wifi=${raw ?: "<null>"} readThrew=$readThrew " +
                    "probe=${port ?: "none"} brand=${Build.BRAND} sdk=${Build.VERSION.SDK_INT}"
            )
            val status = if (port != null) STATUS_OK else STATUS_UNKNOWN
            wirelessProbeCache = WirelessProbe(status, SystemClock.elapsedRealtime())
            return status
        }
    }

    /**
     * Wi-Fi 是否已连接。无线调试的前提：未连 Wi-Fi 时系统会灰掉或自动
     * 关闭无线调试。只认 WLAN 传输（手机蜂窝网络不算）；不要求能上外网。
     */
    private fun checkWifi(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as android.net.ConnectivityManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            if (caps != null &&
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)
            ) {
                STATUS_OK
            } else {
                STATUS_OFF
            }
        } else {
            @Suppress("DEPRECATION")
            val info = cm.activeNetworkInfo
            @Suppress("DEPRECATION")
            if (info != null && info.isConnected &&
                info.type == android.net.ConnectivityManager.TYPE_WIFI
            ) {
                STATUS_OK
            } else {
                STATUS_OFF
            }
        }
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

    /** 厂商或品牌名命中任一关键字即视为该阵营（大小写不敏感） */
    private fun brandMatches(vararg keys: String): Boolean = keys.any { k ->
        Build.MANUFACTURER?.lowercase()?.contains(k) == true ||
            Build.BRAND?.lowercase()?.contains(k) == true
    }

    // OPPO / 一加（OnePlus）/ realme 同为 ColorOS 系，开发者选项路径一致
    val isOppo: Boolean by lazy { brandMatches("oppo", "oneplus", "realme") }

    // vivo / iQOO 同为 OriginOS/Funtouch 系
    val isVivo: Boolean by lazy { brandMatches("vivo", "iqoo") }

    // 华为：覆盖 EMUI（Android 10 及以下）与鸿蒙（HarmonyOS 2/3/4）。
    // 注意：鸿蒙通过 HarmonyOsDetector 单独判定（主要用于无线调试能力），
    // 但品牌展示/保活项下发统一用 isHuawei，避免 EMUI 华为掉到 other。
    val isHuawei: Boolean by lazy { brandMatches("huawei") }

    // 荣耀独立后的 MagicOS 机型（分家前的老荣耀仍是鸿蒙，已在上面归 huawei）
    val isHonor: Boolean by lazy { brandMatches("honor") }

    /**
     * 打开各检查项对应的设置页。返回 true 表示成功拉起了某个页面。
     * 尽量直达；ROM 页面缺失时逐级回退，最终回退应用详情页。
     */
    fun openSetting(context: Context, key: String): Boolean {
        val appContext = context.applicationContext
        return when (key) {
            // 无线调试：优先直达系统「无线调试」子页（少点一层），
            // 组件不存在/未导出（部分 ROM）时回退开发者选项主页
            // （开发者模式未解锁时再回退「关于手机」页）
            "wireless_debug" -> openWirelessDebugSettings(appContext)

            // Wi-Fi：直达系统 WLAN 设置页
            "wifi_connected" ->
                launch(appContext, Intent(Settings.ACTION_WIFI_SETTINGS))

            // 开发者模式未解锁：开发者选项页还不存在，ACTION_APPLICATION_
            // DEVELOPMENT_SETTINGS 会打不开/被重定向，直达「关于手机」页
            // 连点版本号 7 次
            "developer_options" -> openAboutDeviceSettings(appContext)

            // 这些开关都在开发者选项里：未解锁时同样先去「关于手机」
            "adb_master", "miui_adb_secure",
            "vivo_usb_simulate", "oppo_permission_monitor" ->
                devSettingsEntry(appContext)

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

            "miui_battery_unrestricted" -> openMiuiBatterySettings(appContext)

            // One UI「后台使用限制/休眠应用」页无公开 intent，
            // 打开电池优化设置页（含本应用电池"不受限制"开关），再按文案操作
            "samsung_sleep_apps" -> openBatterySettings(appContext)

            // vivo/iQOO 私有三件套：设置页组件名随 OriginOS 版本变化，
            // 逐个尝试，全部失败回退应用详情页（详情页内也有对应入口）
            "vivo_autostart" -> openVivoAutoStart(appContext)

            "vivo_background_power" -> openVivoBackgroundPower(appContext)

            "vivo_power_save" -> openVivoPowerSettings(appContext)

            // 华为（EMUI / 鸿蒙）私有三件套：手机管家/设置的组件名随
            // EMUI/HarmonyOS 版本变化，逐个尝试，失败回退应用详情页
            // （详情页内有「启动管理」「电池」入口）
            "huawei_autostart" -> openHuaweiAutoStart(appContext)

            "huawei_background_power" -> openHuaweiBackgroundPower(appContext)

            "huawei_power_save" -> openHuaweiPowerSettings(appContext)

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
        return devSettingsEntry(context)
    }

    /**
     * 「关于手机」页（连点版本号 7 次解锁开发者模式的页面）。
     *
     * MIUI/HyperOS 必须优先用显式组件直达「全部参数与信息」页：
     * 通用 ACTION_DEVICE_INFO_SETTINGS 在部分 MIUI/HyperOS 版本上被
     * 系统路由到检查解锁状态的页面——startActivity 不抛异常但页面
     * 弹「请先打开开发者模式」toast 后立即 finish（假成功），用户看到
     * 一闪而过回到 App。显式组件不会被 MIUI intent router 改写。
     * 其他品牌先走系统通用 Intent。
     * 全部失败回退设置主页（用户手动找关于手机）。
     */
    private fun openAboutDeviceSettings(context: Context): Boolean {
        if (isMiui) {
            // HyperOS 2 新版「全部参数」页
            launchSettingsComponent(
                context, "com.android.settings.Settings\$AllSpecActivity"
            ) && return true
            // MIUI 11 ~ HyperOS 1 主流「全部参数与信息」页
            launchSettingsComponent(
                context, "com.android.settings.Settings\$MyDeviceInfoActivity"
            ) && return true
            // 旧版 MIUI 的关于页
            launchSettingsComponent(
                context, "com.android.settings.Settings\$DeviceInfoSettingsActivity"
            ) && return true
        }
        if (launch(context, Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))) {
            return true
        }
        // 非小米品牌/通用 action 失败时也兜底试一次显式组件
        launchSettingsComponent(
            context, "com.android.settings.Settings\$DeviceInfoSettingsActivity"
        ) && return true
        return launch(context, Intent(Settings.ACTION_SETTINGS))
    }

    /**
     * 显式启动 com.android.settings 内的 Activity；组件不存在或未导出
     * （startActivity 抛异常）时返回 false，由调用方尝试下一个候选。
     */
    private fun launchSettingsComponent(context: Context, className: String): Boolean {
        return try {
            val intent = Intent().setComponent(
                ComponentName("com.android.settings", className)
            )
            launch(context, intent)
        } catch (e: Throwable) {
            false
        }
    }

    /**
     * 开发者选项入口：开发者模式已解锁时直达开发者选项主页；
     * 未解锁时该页面尚不存在，改去「关于手机」页解锁，
     * 避免点「去开启」后跳转失败/被系统重定向。
     */
    private fun devSettingsEntry(context: Context): Boolean {
        val unlocked = globalStatus(context, "development_settings_enabled") == STATUS_OK
        if (unlocked &&
            launch(context, Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
        ) {
            return true
        }
        return openAboutDeviceSettings(context)
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
     * vivo/iQOO「自启动」页：优先 i管家（permissionmanager）的自启动管理，
     * 再试旧版 i管家（iqoo.secure）的白名单页。组件不存在/未导出时
     * startActivity 抛异常被 [launch] 吞掉，逐级回退应用详情页。
     */
    private fun openVivoAutoStart(context: Context): Boolean {
        val candidates = listOf(
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
            ),
            ComponentName(
                "com.vivo.permissionmanager",
                "com.vivo.permissionmanager.activity.PurviewTabActivity"
            ),
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
            ),
            ComponentName(
                "com.iqoo.secure",
                "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
            )
        )
        candidates.forEach { if (launch(context, Intent().setComponent(it))) return true }
        return openAppDetails(context)
    }

    /**
     * vivo/iQOO「后台耗电管理」页：该页是电池引擎的私有 Activity，
     * 不同 OriginOS 版本组件名不同，逐个尝试；全部失败时打开应用详情页
     * （详情页内有「电池 → 后台耗电管理」入口，用户按文案选择
     * 「允许后台高耗电」）。
     */
    private fun openVivoBackgroundPower(context: Context): Boolean {
        val candidates = listOf(
            ComponentName(
                "com.vivo.pem",
                "com.vivo.pem.activity.HighPowerActivity"
            ),
            ComponentName(
                "com.vivo.pem",
                "com.vivo.pem.ui.HighPowerAppsActivity"
            )
        )
        candidates.forEach { if (launch(context, Intent().setComponent(it))) return true }
        return openAppDetails(context)
    }

    /**
     * vivo/iQOO 省电/睡眠模式：无稳定私有入口，直接打开系统「省电模式」
     * 设置页（多数 OriginOS 版本即电池页），失败回退设置主页，
     * 用户按文案关闭省电/睡眠模式、排除异常耗电优化。
     */
    private fun openVivoPowerSettings(context: Context): Boolean {
        if (launch(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) return true
        return launch(context, Intent(Settings.ACTION_SETTINGS))
    }

    /**
     * 华为「应用启动管理」页：EMUI 在「设置 → 应用 → 应用启动管理」，
     * 鸿蒙在「手机管家 → 应用启动管理」。组件名随版本变化，逐个尝试；
     * 全部失败时回退应用详情页（详情页内有「启动管理」入口）。
     * 用户需关闭「自动管理」，手动允许「自启动 / 关联启动 / 后台活动」。
     */
    private fun openHuaweiAutoStart(context: Context): Boolean {
        val candidates = listOf(
            // EMUI 10/11 应用启动管理
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
            ),
            // 鸿蒙 / 部分 EMUI 手机管家启动管理
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
            // 鸿蒙 4 手机管家
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupAppListActivity"
            )
        )
        candidates.forEach { if (launch(context, Intent().setComponent(it))) return true }
        return openAppDetails(context)
    }

    /**
     * 华为「耗电详情 / 电池」页：EMUI 在「设置 → 电池 → 耗电详情」，
     * 鸿蒙在「手机管家 → 电池」。打开后找到本应用，允许后台活动、
     * 关闭「自动管理」/异常耗电优化。失败回退系统电池页，再回退
     * 应用详情页（详情页内有「电池」入口）。
     */
    private fun openHuaweiBackgroundPower(context: Context): Boolean {
        val candidates = listOf(
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            ),
            ComponentName(
                "com.android.settings",
                "com.android.settings.Settings\$AdvancedPowerSettingsActivity"
            )
        )
        candidates.forEach { if (launch(context, Intent().setComponent(it))) return true }
        if (launch(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) return true
        return openAppDetails(context)
    }

    /**
     * 华为省电模式页：打开系统「省电模式 / 电池」设置页，用户按文案
     * 关闭省电模式、把本应用加入不优化名单。失败回退设置主页。
     */
    private fun openHuaweiPowerSettings(context: Context): Boolean {
        if (launch(context, Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))) return true
        return launch(context, Intent(Settings.ACTION_SETTINGS))
    }

    /**
     * MIUI/HyperOS 单应用「省电策略」页（无限制/省电推荐/无限制后台）。
     * 该页是 powerkeeper 私有 Activity，组件名随 MIUI 代际变化，只尝试
     * 现役版本的入口；失败时回退系统"不优化电池"弹窗，再回退应用详情页
     * （详情页内也有「省电策略」入口）。
     * 注：MIUI 旧版「神隐模式」配置页（HiddenAppsConfigActivity）已不再
     * 适配，那些远古版本不在支持范围内。
     */
    private fun openMiuiBatterySettings(context: Context): Boolean {
        val label = runCatching {
            context.packageManager.getApplicationLabel(context.applicationInfo).toString()
        }.getOrDefault(context.packageName)
        // MIUI 12/13 ~ HyperOS 的应用电池详情页
        val direct = Intent().setComponent(
            ComponentName(
                "com.miui.powerkeeper",
                "com.miui.powerkeeper.ui.apps.detail.AppDetailActivity"
            )
        ).putExtra("package_name", context.packageName)
            .putExtra("package_label", label)
        if (launch(context, direct)) return true
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
