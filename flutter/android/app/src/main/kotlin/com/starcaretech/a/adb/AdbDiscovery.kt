package com.starcaretech.a.adb

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 发现 adbd 的 mDNS 服务：
 *  - `_adb-tls-pairing._tcp`：无线调试"使用配对码配对设备"时广播（含端口）
 *  - `_adb-tls-connect._tcp`：无线调试主服务（TLS ADB 端口）
 *
 * 校验规则（源自 Shizuku AdbMdns，避免连到失效记录）：
 *  1. 解析出的 host 必须是本机某张网卡的地址（同机回环连接场景）
 *  2. 该端口必须已在 127.0.0.1 上监听（bind 失败=端口被占用=有服务监听）
 *     NsdManager 可能返回上一次无线调试会话的陈旧记录，不校验会直接 ECONNREFUSED
 */
// 仅 Android 11+ 路径使用；getAttributes() 为 API 33，低版本走 try/catch 兜底
@SuppressLint("NewApi")
object AdbDiscovery {

    private const val TAG = "AdbDiscovery"

    const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
    const val TYPE_CONNECT = "_adb-tls-connect._tcp"

    /**
     * 最近一次成功发现的 connect 端口。
     * adbd 重开无线调试后端口通常会变，但部分 ROM 会复用；
     * 重连时先探活这个端口（一次 bind 探测，毫秒级），命中可直接跳过
     * 冷启动 NsdManager 数秒的发现延迟。仅缓存"当时确实在监听"的端口，
     * 使用前一律重新 bind 探活，陈旧记录不会被使用。
     *
     * 同时持久化到 SharedPreferences：App 被从最近任务划掉/进程被杀后，
     * 无线调试往往根本没重启、端口不变，新进程可直接命中旧端口，绕开
     * 国产 ROM 上 NsdManager 冷启动发现不到常驻 connect 服务的顽疾
     * （pairing 服务靠注册时的主动 announce 总能收到，connect 服务只回应
     * 主动 query，部分 ROM 的 mdns 栈对 query 响应很差）。
     */
    private const val PREFS = "adb_auth"
    private const val KEY_LAST_CONNECT_PORT = "last_connect_port"

    @Volatile
    private var memPort: Int? = null

    /** 取缓存端口（内存优先，其次持久化存储）；使用前必须重新 bind 探活 */
    fun cachedPort(context: Context): Int? {
        memPort?.let { return it }
        val app = context.applicationContext
        return runCatching {
            val sp = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val p = sp.getInt(KEY_LAST_CONNECT_PORT, 0)
            if (p > 0) p else null
        }.getOrNull()?.also { memPort = it }
    }

    fun rememberConnectPort(context: Context, port: Int) {
        if (port <= 0) return
        memPort = port
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_LAST_CONNECT_PORT, port)
                .apply()
        }
    }

    class DiscoveredService(
        val serviceName: String,
        val host: String,
        val port: Int,
        /** TXT 记录（如配对服务的 pn=设备名） */
        val attributes: Map<String, String>
    )

    /** 解析出的地址是否属于本机网卡 */
    fun isLocalAddress(host: String?): Boolean {
        if (host == null) return false
        return try {
            NetworkInterface.getNetworkInterfaces().toList().any { nic ->
                nic.inetAddresses.toList().any { it.hostAddress == host }
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * 127.0.0.1:port 是否已被监听。
     * 用 bind 探测而非主动 connect：避免在配对前与 adbd 建立无协议的半截连接。
     */
    fun isLoopbackPortListening(port: Int): Boolean = try {
        ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", port), 1) }
        false
    } catch (e: IOException) {
        true
    } catch (e: Exception) {
        false
    }

    /**
     * 快速探测无线调试是否真的在运行（必须在非主线程调用）。
     *
     * 部分 ROM（实测 MIUI/HyperOS）对普通应用隐藏/保护 Settings.Global
     * 的 "adb_wifi" 键，读不到开关值；但无线调试开着时 adbd 一定会注册
     * _adb-tls-connect 服务，且端口在回环真实监听——直接探测服务比读
     * 被屏蔽的设置值可靠。顺序：缓存端口 bind 探活（毫秒级）→ 短窗 mDNS
     * → 固定 5555 兜底。命中同时记住端口，后续 shell 重连可直接复用。
     *
     * @return 可用端口；未发现返回 null
     */
    fun quickProbeConnectPort(context: Context, timeoutMs: Long = 3_500L): Int? {
        cachedPort(context)?.let { p ->
            if (isLoopbackPortListening(p)) return p
        }
        val s = findFirst(context, TYPE_CONNECT, timeoutMs = timeoutMs)
        if (s != null) {
            rememberConnectPort(context, s.port)
            return s.port
        }
        return if (isLoopbackPortListening(5555)) 5555 else null
    }

    private fun toService(info: NsdServiceInfo): DiscoveredService? {
        val host = info.host?.hostAddress ?: return null
        if (!isLocalAddress(host)) {
            Log.i(TAG, "忽略非本机地址服务: ${info.serviceName} $host:${info.port}")
            return null
        }
        if (!isLoopbackPortListening(info.port)) {
            Log.i(TAG, "忽略回环未监听的服务（可能是陈旧记录）: ${info.serviceName} :${info.port}")
            return null
        }
        val attrs = HashMap<String, String>()
        // getAttributes() 是 API 33 新增，低版本直接忽略 TXT
        try {
            @Suppress("DEPRECATION")
            val raw = info.attributes
            raw?.forEach { (k, v) -> if (v != null) attrs[k] = String(v, Charsets.US_ASCII) }
        } catch (e: NoSuchMethodError) {
            // Android 11/12 无此方法
        }
        return DiscoveredService(info.serviceName, host, info.port, attrs)
    }

    /**
     * 阻塞扫描第一个"有效"的匹配服务。必须在非主线程调用。
     * @param timeoutMs 最长等待时间
     * @param nameFilter 服务名过滤（null 表示任意）
     */
    fun findFirst(context: Context, serviceType: String, timeoutMs: Long, nameFilter: String? = null): DiscoveredService? {
        val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        val thread = HandlerThread("AdbNsd").apply { start() }
        val handler = Handler(thread.looper)
        var found: DiscoveredService? = null
        val latch = CountDownLatch(1)
        val lock = Any()
        // 部分 ROM 的 WiFi 驱动在 App 未持多播锁时过滤入站 mDNS 多播包，
        // 导致 NsdManager 发了 query 却收不到常驻 connect 服务的响应。
        // 扫描期间持锁（normal 权限，安装即授予），结束立即释放
        val multicastLock = runCatching {
            val wifi = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.createMulticastLock("rustdesk_adb_mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {}
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                latch.countDown()
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onDiscoveryStopped(serviceType: String?) {}
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo == null) return
                if (nameFilter != null && !serviceInfo.serviceName.contains(nameFilter)) return
                if (found != null) return
                nsdManager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                        // 解析失败不结束扫描，继续等下一个广播，直到超时
                    }

                    override fun onServiceResolved(info: NsdServiceInfo?) {
                        if (info == null) return
                        val service = toService(info) ?: return
                        synchronized(lock) {
                            if (found == null) {
                                found = service
                                latch.countDown()
                            }
                        }
                    }
                })
            }
        }

        try {
            handler.post { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener) }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
            runCatching { thread.quitSafely() }
            runCatching { if (multicastLock?.isHeld == true) multicastLock.release() }
        }
        return synchronized(lock) { found }
    }

    /**
     * 持续发现器：供前台 Service 在用户操作期间长期监听。
     * 发现有效端口回调 [onValidPort]；当前服务消失回调 [onLost]。
     * start/stop 必须成对调用，内部自行处理 NsdManager 回调线程。
     */
    class Continuous(
        context: Context,
        private val serviceType: String,
        private val onValidPort: (Int) -> Unit,
        private val onLost: (() -> Unit)? = null
    ) {
        private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        // NsdManager 在调用 discoverServices 的线程（按其 Looper）派发所有回调。
        // 必须用专用 HandlerThread：回调里要做 ServerSocket 端口探测，
        // 在主线程执行会抛 NetworkOnMainThreadException 直接杀掉前台服务。
        private val thread = HandlerThread("AdbNsdContinuous").apply { start() }
        private val handler = Handler(thread.looper)
        @Volatile private var running = false
        private var registered = false
        @Volatile private var serviceName: String? = null

        private val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) { registered = true }
            override fun onDiscoveryStopped(serviceType: String?) { registered = false }
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                registered = false
                // 部分 ROM 后台时短暂失败，稍后系统会重试；仍保持 running 状态
            }
            override fun onStopDiscoveryFailed(serviceType: String?, errorCode: Int) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                if (!running || serviceInfo == null) return
                // NsdManager 同一时刻只允许一个未完成 resolve；失败忽略即可，服务广播会重复
                runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                if (serviceInfo?.serviceName == serviceName) {
                    serviceName = null
                    onLost?.invoke()
                }
            }
        }

        private val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {}
            override fun onServiceResolved(info: NsdServiceInfo?) {
                if (!running || info == null) return
                val service = toService(info) ?: return
                serviceName = info.serviceName
                onValidPort(service.port)
            }
        }

        fun start() {
            if (running) return
            running = true
            handler.post {
                if (running) {
                    runCatching { nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener) }
                }
            }
        }

        fun stop() {
            if (!running) return
            running = false
            // registered 只在 handler 线程读写，整段 post 过去避免竞态
            handler.post {
                if (registered) {
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                    registered = false
                }
            }
            serviceName = null
        }

        /** 服务彻底销毁时释放线程；之后不可再 start */
        fun release() {
            running = false
            handler.post {
                if (registered) {
                    runCatching { nsdManager.stopServiceDiscovery(listener) }
                    registered = false
                }
                thread.quitSafely()
            }
        }
    }
}
