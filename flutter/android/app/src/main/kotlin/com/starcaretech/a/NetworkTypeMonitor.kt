package com.starcaretech.a

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import ffi.FFI

/**
 * 网络质量探测（Probe）：识别当前默认网络的传输类型，写 Rust 本地选项
 * `net-transport`（wifi/cellular/ethernet/unknown），心跳组包时读取，
 * 服务端据此做"省份×运营商×网络类型"矩阵槽位匹配。
 *
 * - 仅需 ACCESS_NETWORK_STATE（AndroidManifest 已声明），不申请新权限
 * - NetworkCallback 事件驱动，无轮询；注册后主动对齐一次，覆盖机型不立即回调
 * - minSdk 22 兼容：API 24+ 用 registerDefaultNetworkCallback，
 *   API 22/23 退回 registerNetworkCallback；读网络分别走 Capabilities / NetworkInfo
 * - 进程级生命周期：由 MainApplication.onCreate 启动，App 进程存活（含用户未开启
 *   远程服务、看门狗/开机广播以纯后台方式拉起）即持续采集，与 MainService 的
 *   stop-service 完全解耦——探测"在线"以 App 心跳为准，transport 也必须同生命周期。
 *   进程死亡后回调由系统自动注销，无需手动 stop。
 *
 * 时序说明：Application 阶段 Rust 端 APP_DIR（config 落盘根目录）可能尚未由
 * Dart mainInit / FFI.startServer 写入，此时 FFI.setOption 的落盘会静默失败，
 * 但内存 LOCAL_CONFIG 写入成功，同进程后续 mainGetOption 读内存即可得到值；
 * APP_DIR 就绪后的下一次网络变化或进程重启重新注册时会正常落盘。
 */
object NetworkTypeMonitor {
    private const val TAG = "NetworkTypeMonitor"
    const val OPTION_KEY = "net-transport"

    private var cm: ConnectivityManager? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var lastType: String? = null

    fun start(ctx: Context) {
        if (callback != null) return
        val manager =
            ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (manager == null) {
            Log.w(TAG, "ConnectivityManager unavailable")
            return
        }
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = update()
            override fun onLost(network: Network) = update()
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) = update()
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
        update()
        Log.i(TAG, "network type monitor started")
    }

    fun stop() {
        // 进程级监听后正常生命周期内不再调用：进程死亡时系统自动注销回调。
        // 保留仅为极端场景（如测试）提供手动收口能力。
        val cb = callback ?: return
        runCatching { cm?.unregisterNetworkCallback(cb) }
            .onFailure { Log.w(TAG, "unregister network callback failed", it) }
        callback = null
        cm = null
        lastType = null
        Log.i(TAG, "network type monitor stopped")
    }

    private fun update() {
        val manager = cm ?: return
        val type = runCatching { readCurrentType(manager) }.getOrDefault("unknown")
        if (type == lastType) return
        lastType = type
        runCatching { FFI.setOption(OPTION_KEY, type) }
            .onFailure { Log.w(TAG, "FFI.setOption($OPTION_KEY,$type) failed", it) }
        Log.i(TAG, "$OPTION_KEY=$type")
    }

    private fun readCurrentType(manager: ConnectivityManager): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val caps = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
                ?: return "unknown"
            return when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
                else -> "unknown"
            }
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
}
