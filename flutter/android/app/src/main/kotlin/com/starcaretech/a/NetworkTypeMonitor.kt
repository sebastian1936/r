package com.starcaretech.a

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import ffi.FFI

/**
 * 网络质量探测（Probe）：识别当前默认网络的传输类型，写 Rust 本地选项
 * `net-transport`（wifi/cellular/ethernet/unknown），心跳组包时读取，
 * 服务端据此做"省份×运营商×网络类型"矩阵槽位匹配。
 *
 * - 仅需 ACCESS_NETWORK_STATE（AndroidManifest 已声明），不申请新权限
 * - 双层采集保障：
 *   1. 事件驱动：onCapabilitiesChanged 直接采用回调携带的 capabilities 判定，
 *      不再绕全局 activeNetwork——进程刚被创建（看门狗/开机拉起、ROM 冷冻解冻）
 *      时 activeNetwork 可能短暂为 null，但事件参数里的 caps 已是当前网络真实状态；
 *   2. 定时兜底：每 15s（与 App 心跳同频）重读一次，配合 lastType 去重，网络未变
 *      零写入。解决"启动竞态写入 unknown 后蜂窝稳定期再无事件、错误值长期挂住"的
 *      问题（实测小米 13 Pro 蜂窝下 unknown 持续 73s，切 Wi-Fi 才被事件纠正）。
 * - minSdk 22 兼容：API 24+ 用 registerDefaultNetworkCallback，
 *   API 22/23 退回 registerNetworkCallback；读网络分别走 Capabilities / NetworkInfo
 * - 进程级生命周期：由 MainApplication.onCreate 启动，App 进程存活（含用户未开启
 *   远程服务、看门狗/开机广播以纯后台方式拉起）即持续采集，与 MainService 的
 *   stop-service 完全解耦——探测"在线"以 App 心跳为准，transport 也必须同生命周期。
 *   进程死亡后回调与 Handler 任务由系统自动回收。
 *
 * 时序说明：FFI.onAppStart（MainApplication.onCreate，先于本 monitor 启动）
 * 已通过 Context.getDir("flutter") 提前写入 Rust 端 APP_DIR，因此这里的
 * FFI.setOption 可正常落盘；早期实现中 APP_DIR 空窗导致的静默落盘失败已修复。
 */
object NetworkTypeMonitor {
    private const val TAG = "NetworkTypeMonitor"
    const val OPTION_KEY = "net-transport"

    /**
     * 兜底刷新间隔，与 App 心跳（15s）同频：任一心跳周期内 transport 至少被
     * 重估一次，事件丢失/启动竞态产生的错误值最多 15s 内自愈。
     */
    private const val REFRESH_INTERVAL_MS = 15_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastType: String? = null

    /** 定时兜底：仅重读 + 去重，网络没变化时不会产生 FFI 写入。 */
    private val refreshTask = object : Runnable {
        override fun run() {
            update("tick")
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    fun start(ctx: Context) {
        if (callback != null) return
        val manager =
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            Log.w(TAG, "ConnectivityManager unavailable")
            return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update("onAvailable")
            override fun onLost(network: Network) = update("onLost")
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) = updateWithCapabilities(capabilities, "onCapabilitiesChanged")
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // 监听系统默认网络，自动跟随 Wi-Fi/蜂窝切换
                manager.registerDefaultNetworkCallback(cb)
            } else {
                // API 22/23：无 default callback，注册全量 NetworkRequest 兜底
                manager.registerNetworkCallback(NetworkRequest.Builder().build(), cb)
            }
        }.onFailure {
            Log.w(TAG, "register network callback failed", it)
            return
        }
        cm = manager
        callback = cb
        // 立即对齐：服务被看门狗重启后也能马上写对当前网络
        update("start")
        mainHandler.postDelayed(refreshTask, REFRESH_INTERVAL_MS)
        Log.i(TAG, "network type monitor started")
    }

    fun stop() {
        // 进程级监听后正常生命周期内不再调用：进程死亡时系统自动注销回调与 Handler 任务。
        // 保留仅为极端场景（如测试）提供手动收口能力。
        mainHandler.removeCallbacks(refreshTask)
        val cb = callback ?: return
        runCatching { cm?.unregisterNetworkCallback(cb) }
            .onFailure { Log.w(TAG, "unregister network callback failed", it) }
        callback = null
        cm = null
        lastType = null
        Log.i(TAG, "network type monitor stopped")
    }

    /** 全局活动网络重读（start 即时对齐 + tick 兜底走这里）。 */
    private fun update(source: String) {
        val manager = cm ?: return
        val type = runCatching { readCurrentType(manager) }.getOrDefault("unknown")
        applyType(type, source)
    }

    /** 事件回调路径：直接采用系统下发的 capabilities，规避 activeNetwork 启动竞态。 */
    private fun updateWithCapabilities(caps: NetworkCapabilities, source: String) {
        applyType(typeOfCapabilities(caps), source)
    }

    private fun applyType(type: String, source: String) {
        if (type == lastType) return
        Log.i(TAG, "$OPTION_KEY=$type (source=$source, last=$lastType)")
        lastType = type
        runCatching { FFI.setOption(OPTION_KEY, type) }
            .onFailure { Log.w(TAG, "FFI.setOption($OPTION_KEY,$type) failed", it) }
    }

    private fun readCurrentType(manager: ConnectivityManager): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = manager.activeNetwork
            if (network == null) {
                // 启动竞态/系统网络栈未就绪：留给下一次 tick 或网络事件纠正
                Log.w(TAG, "activeNetwork=null, report unknown temporarily")
                return "unknown"
            }
            val caps = manager.getNetworkCapabilities(network) ?: return "unknown"
            return typeOfCapabilities(caps)
        }
        // API 22：NetworkCapabilities 尚未公开，用已废弃但仍生效的 NetworkInfo
        @Suppress("DEPRECATION")
        return when (manager.activeNetworkInfo?.type) {
            ConnectivityManager.TYPE_MOBILE -> "cellular"
            ConnectivityManager.TYPE_WIFI -> "wifi"
            ConnectivityManager.TYPE_ETHERNET -> "ethernet"
            else -> "unknown"
        }
    }

    private fun typeOfCapabilities(caps: NetworkCapabilities): String = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        else -> "unknown"
    }
}
