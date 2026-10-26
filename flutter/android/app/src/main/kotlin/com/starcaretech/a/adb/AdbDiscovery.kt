package com.starcaretech.a.adb

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.IOException
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
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
    /**
     * 上次成功连接所用 host。手动输入兜底机型上 connect 端口只在 wlan 地址可达、
     * 回环探不到（如 OriginOS Y35），必须连同 host 一起缓存，重连才能复用。
     * 旧版本只存过端口、无此键时按 127.0.0.1 处理（与旧行为一致）。
     */
    private const val KEY_LAST_CONNECT_HOST = "last_connect_host"

    @Volatile
    private var memPort: Int? = null

    /** 取缓存端口（内存优先，其次持久化存储）；使用前必须重新探活 */
    fun cachedPort(context: Context): Int? = cachedEndpoint(context)?.port

    /**
     * 缓存的连接端点（含 host）。读取时实时探活：
     *  - 回环地址：ServerSocket bind 探测（不与 adbd 建立半截连接）
     *  - 其他地址（手动输入的 wlan IP）：短超时真实 TCP 连接探测
     * 端口随无线调试重启会变，探活失败即视为无缓存。
     */
    fun cachedEndpoint(context: Context): DiscoveredService? {
        val app = context.applicationContext
        val sp = runCatching {
            app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull() ?: return null
        val port = memPort ?: runCatching {
            sp.getInt(KEY_LAST_CONNECT_PORT, 0).takeIf { it > 0 }
        }.getOrNull()?.also { memPort = it } ?: return null
        val host = runCatching {
            sp.getString(KEY_LAST_CONNECT_HOST, null)
        }.getOrNull()?.takeIf { it.isNotBlank() } ?: "127.0.0.1"
        val alive = if (host == "127.0.0.1" || host == "localhost") {
            isLoopbackPortListening(port)
        } else {
            isTcpReachable(host, port, 400)
        }
        return if (alive) DiscoveredService("cached", host, port, emptyMap()) else null
    }

    fun rememberConnectPort(context: Context, port: Int) =
        rememberConnectEndpoint(context, "127.0.0.1", port)

    fun rememberConnectEndpoint(context: Context, host: String, port: Int) {
        if (port <= 0 || host.isBlank()) return
        memPort = port
        runCatching {
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_LAST_CONNECT_PORT, port)
                .putString(KEY_LAST_CONNECT_HOST, host)
                .apply()
        }
    }

    /**
     * 对非回环地址做短超时真实 TCP 连接探测（用于手动输入的 wlan IP）。
     * 仅确认端口有服务应答；是否真是 adbd 由后续 ADB 协议握手验证。
     */
    fun isTcpReachable(host: String, port: Int, timeoutMs: Int = 500): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
        true
    } catch (e: Exception) {
        false
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
     * →（可选）独立第二轮 mDNS → 固定 5555 兜底。命中同时记住端口，
     * 后续 shell 重连可直接复用。
     *
     * [extraRoundMs] > 0 时，首轮整窗未发现会用全新的 NsdManager 发现会话
     * 再扫一轮：部分 ROM（实测 OriginOS）上 NsdManager 冷启动首轮对常驻
     * connect 服务的 query 响应整窗丢失（start 成功但无任何回调），重开
     * 一轮发现即可收到。envInfo 等得起几秒的场景传 3000；恢复热路径传 0。
     *
     * @return 可用端口；未发现返回 null
     */
    fun quickProbeConnectPort(
        context: Context,
        timeoutMs: Long = 3_500L,
        extraRoundMs: Long = 0L
    ): Int? {
        // cachedEndpoint 内部已按 host 类型探活（回环 bind / wlan TCP）
        cachedEndpoint(context)?.let { return it.port }
        var s = findFirst(context, TYPE_CONNECT, timeoutMs = timeoutMs)
        if (s == null && extraRoundMs > 0) {
            Log.i(TAG, "首轮 mDNS 未发现 connect 服务，${extraRoundMs}ms 内重开一轮发现")
            s = findFirst(context, TYPE_CONNECT, timeoutMs = extraRoundMs)
        }
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

        // NsdManager.discoverServices 在以下场景会瞬时启动失败（错误码
        // ERROR_MAX_LIMIT=4 / ERROR_INTERNAL_ERROR=0）：App 被从最近任务
        // 划掉后同进程 Activity 重建、上一轮发现的 stop 尚未完成、国产 ROM
        // 后台限制。旧逻辑一旦 onStartDiscoveryFailed 立即放弃整轮扫描，
        // 表现为 mDNS 秒回"未发现服务"（shell_no_service），用户即使停在
        // 无线调试页、服务确实在广播也连不上。改为短退避重启，最多 3 次。
        var discoveryActive = false
        var startRetries = 0
        val maxStartRetries = 3

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String?) {
                discoveryActive = true
            }
            override fun onStartDiscoveryFailed(serviceType: String?, errorCode: Int) {
                discoveryActive = false
                Log.w(TAG, "discoverServices 启动失败 code=$errorCode，retries=$startRetries/$maxStartRetries")
                if (latch.count > 0 && startRetries < maxStartRetries) {
                    startRetries++
                    handler.postDelayed({
                        if (latch.count > 0) {
                            runCatching {
                                nsdManager.discoverServices(
                                    serviceType ?: TYPE_CONNECT,
                                    NsdManager.PROTOCOL_DNS_SD,
                                    this
                                )
                            }.onFailure {
                                // post 内同步抛出（如 listener 已被占用）也走重试节奏
                                onStartDiscoveryFailed(serviceType, -1)
                            }
                        }
                    }, 800)
                } else {
                    latch.countDown()
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo?) {}
            override fun onDiscoveryStopped(serviceType: String?) {
                discoveryActive = false
            }
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
            handler.post {
                runCatching {
                    nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
                }.onFailure {
                    discoveryListener.onStartDiscoveryFailed(serviceType, -1)
                }
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            // stop/quit 必须投递到发起 discoverServices 的同一 Looper，
            // 避免跨线程 stop 与下一轮 start 竞态
            handler.post {
                if (discoveryActive) {
                    runCatching { nsdManager.stopServiceDiscovery(discoveryListener) }
                }
                thread.quitSafely()
            }
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
        @Volatile private var serviceName: String? = null

        // 配对期 App 退在后台（前台是系统设置页），部分 ROM（实测 ColorOS/OriginOS）
        // 的 WiFi 驱动在 App 不持多播锁时直接过滤入站 mDNS 多播，adbd 配对服务
        // 进入页面时那一小撮主动广播会被整撮丢掉。全程持锁（非引用计数），
        // stop/release 时释放。一次性 findFirst 路径同样持锁，此处对齐。
        private val multicastLock = runCatching {
            context.applicationContext
                .getSystemService(Context.WIFI_SERVICE)
                ?.let { it as? WifiManager }
                ?.createMulticastLock("rustdesk_adb_mdns_cont")
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()

        // 看门狗 + 双通道发现，针对 ColorOS 16 / OriginOS 等 ROM 实测顽疾：
        // 1) 系统 mdns 守护进程对"新建立的发现会话"会立即重推缓存里的已知服务，
        //    但重开会话必须使用【全新的 listener 对象】——实测 ColorOS 16 上
        //    stopServiceDiscovery 后用同一 listener 立刻 discoverServices，新会话
        //    会被悄悄挂死（onDiscoveryStarted 照回调，之后零事件），怎么重开都
        //    救不活，表现为首次配对永远"正在搜索"、重建发现器（第二次点击）才好。
        // 2) API 34+ 额外注册 registerServiceInfoCallback：走系统服务另一条实现
        //    路径，注册即推缓存、信息已解析（无需 resolveService），与经典通道
        //    互为冗余，任一通道校验通过即成功。
        // 以"拿到校验通过的有效端口"为唯一成功信号——found 但 resolve 失败/
        // 陈旧记录都不算，会继续轮换。shortService 前台服务约 3 分钟寿命，窗取 150s。
        private val watchdogMs = 6_000L
        private val maxWatchMs = 150_000L
        private var startElapsed = 0L
        @Volatile private var gotValidPort = false

        // ------------------------------------------------- 经典通道（按代轮换）
        private class LegacySession(val gen: Int, val listener: NsdManager.DiscoveryListener) {
            @Volatile var active = false
        }
        private var legacy: LegacySession? = null
        private var legacyKickScheduled = false

        private val kickLegacy = Runnable {
            legacyKickScheduled = false
            if (running && !gotValidPort) restartLegacy()
        }

        private fun scheduleKickLegacy(delayMs: Long) {
            if (!running || gotValidPort || legacyKickScheduled) return
            legacyKickScheduled = true
            handler.postDelayed(kickLegacy, delayMs)
        }

        /**
         * 用【全新 listener】开新一代发现会话，再异步停掉旧代；只在 handler 线程调用。
         * 先建新后停旧：新会话注册的瞬间系统 mdns 就会把缓存里的已知服务重推一次，
         * 这是 ColorOS 16 上唯一可靠的拿到配对服务的时机，不能先停后等。
         * 新旧并存仅毫秒级，系统对同 App 发现会话有 ~10 个名额，并存最多 2 个。
         */
        private fun restartLegacy() {
            if (!running || gotValidPort) return
            val old = legacy
            val newGen = (old?.gen ?: 0) + 1
            startLegacy(newGen)
            if (old != null && old.active) {
                runCatching { nsdManager.stopServiceDiscovery(old.listener) }
            }
        }

        private fun startLegacy(gen: Int) {
            if (!running || gotValidPort) return
            if (legacy?.gen == gen) return
            val listener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String?) {
                    if (legacy?.gen == gen) {
                        legacy?.active = true
                        Log.d(TAG, "legacy gen#$gen started ($serviceType)")
                    }
                }
                override fun onDiscoveryStopped(st: String?) {
                    Log.d(TAG, "legacy gen#$gen stopped")
                }
                override fun onStartDiscoveryFailed(st: String?, errorCode: Int) {
                    Log.d(TAG, "legacy gen#$gen start failed code=$errorCode, retry in 800ms")
                    if (legacy?.gen == gen) legacy?.active = false
                    // ERROR_MAX_LIMIT=4 / INTERNAL_ERROR=0 等：退避重开
                    scheduleKickLegacy(800)
                }
                override fun onStopDiscoveryFailed(st: String?, errorCode: Int) {
                    Log.d(TAG, "legacy gen#$gen stop failed code=$errorCode")
                    // 被替换的旧代停止失败会成为孤儿会话（堆积会撞系统发现名额
                    // 上限 ERROR_MAX_LIMIT）：短延迟再停一次，最多自然重试一轮
                    if (gen < (legacy?.gen ?: 0)) {
                        handler.postDelayed({
                            if (running && !gotValidPort && legacy?.gen != gen) {
                                runCatching { nsdManager.stopServiceDiscovery(this) }
                            }
                        }, 500)
                    }
                }
                override fun onServiceFound(serviceInfo: NsdServiceInfo?) {
                    if (serviceInfo == null || !running || gotValidPort) return
                    if (legacy?.gen != gen) return
                    Log.d(TAG, "legacy gen#$gen found: ${serviceInfo.serviceName}")
                    // 同一时刻只允许一个未完成 resolve；失败忽略，新一代会话会再推
                    runCatching { nsdManager.resolveService(serviceInfo, resolveListener) }
                }
                override fun onServiceLost(serviceInfo: NsdServiceInfo?) {
                    // 成功收口后停止会话的过程中旧代也可能收到 lost，
                    // 不能让它把"请输入配对码"通知打回"正在搜索"
                    if (gotValidPort || legacy?.gen != gen) return
                    if (serviceInfo?.serviceName == serviceName) {
                        serviceName = null
                        onLost?.invoke()
                    }
                }
            }
            legacy = LegacySession(gen, listener)
            try {
                nsdManager.discoverServices(
                    serviceType, NsdManager.PROTOCOL_DNS_SD, listener
                )
            } catch (e: Exception) {
                Log.d(TAG, "legacy gen#$gen discover threw: ${e.javaClass.simpleName}, retry in 800ms")
                legacy?.active = false
                scheduleKickLegacy(800)
            }
        }

        private val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(info: NsdServiceInfo?, errorCode: Int) {
                Log.d(TAG, "legacy resolve failed code=$errorCode for ${info?.serviceName}")
            }
            override fun onServiceResolved(info: NsdServiceInfo?) {
                if (!running || gotValidPort || info == null) return
                val service = toService(info)
                if (service == null) {
                    Log.d(TAG, "legacy resolved but rejected (non-local/stale): ${info.host}:${info.port}")
                    return
                }
                serviceName = info.serviceName
                deliverValid(service.port, "legacy")
            }
        }

        // --------------------------------------------- API34+ 新接口冗余通道
        private var modern: ModernDiscovery? = null

        @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
        private inner class ModernDiscovery {
            private var cb: NsdManager.ServiceInfoCallback? = null
            // 所有回调派发到发现器专用 HandlerThread（toService 里有端口探测，
            // 不能在主线程做网络操作）
            private val executor = Executor { r -> handler.post(r) }

            fun start() {
                stop()
                if (!running || gotValidPort) return
                // ServiceInfoCallback 准确签名（CI 编译报错定位）：
                // onServiceUpdated(NsdServiceInfo) / onServiceLost() 无参
                val callback = object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                        Log.d(TAG, "modern register failed code=$errorCode")
                    }
                    override fun onServiceInfoCallbackUnregistered() {}
                    override fun onServiceUpdated(info: NsdServiceInfo) {
                        if (!running || gotValidPort) return
                        val svc = toService(info) ?: run {
                            Log.d(TAG, "modern updated but rejected (non-local/stale): ${info.host}:${info.port}")
                            return
                        }
                        serviceName = info.serviceName
                        deliverValid(svc.port, "modern(api34)")
                    }
                    override fun onServiceLost() {}
                }
                cb = callback
                try {
                    nsdManager.registerServiceInfoCallback(
                        NsdServiceInfo().apply { serviceType = this@Continuous.serviceType },
                        executor, callback
                    )
                    Log.d(TAG, "modern ServiceInfoCallback registered ($serviceType)")
                } catch (e: Exception) {
                    Log.d(TAG, "modern register threw: ${e.javaClass.simpleName}")
                }
            }

            fun stop() {
                val c = cb ?: return
                cb = null
                // 无条件注销（runCatching 兜底重复/未注册完成的竞态）；
                // 不用 active 标志位——旧注册的异步 unregistered 回调会
                // 误清新注册的状态
                runCatching { nsdManager.unregisterServiceInfoCallback(c) }
            }
        }

        /** 任一通道校验通过后的统一收口 */
        private fun deliverValid(port: Int, source: String) {
            if (!running || gotValidPort) return
            gotValidPort = true
            Log.i(TAG, "valid pairing port $port via $source")
            handler.removeCallbacks(watchdog)
            handler.removeCallbacks(kickLegacy)
            handler.post { stopSessions() }
            onValidPort(port)
        }

        private fun stopSessions() {
            legacy?.let { s ->
                if (s.active) runCatching { nsdManager.stopServiceDiscovery(s.listener) }
            }
            legacy = null
            modern?.stop()
        }

        private val watchdog = object : Runnable {
            override fun run() {
                if (!running || gotValidPort) return
                if (SystemClock.elapsedRealtime() - startElapsed > maxWatchMs) {
                    Log.d(TAG, "continuous watchdog gives up (no valid port in ${maxWatchMs}ms)")
                    return
                }
                // 周期性轮换：新会话让系统重推缓存服务、重发 PTR 查询；
                // 经典通道换新 listener，新接口通道重新注册
                Log.d(TAG, "watchdog: rotating discovery sessions")
                modern?.start()
                restartLegacy()
                handler.postDelayed(this, watchdogMs)
            }
        }

        fun start() {
            if (running) return
            running = true
            gotValidPort = false
            startElapsed = SystemClock.elapsedRealtime()
            acquireMulticastLock()
            handler.post {
                if (!running) return@post
                // API34+ 新接口通道（Find X7/ColorOS 16 等机型的关键冗余路径）
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    if (modern == null) modern = ModernDiscovery()
                    modern?.start()
                }
                // 经典通道第一代
                restartLegacy()
                handler.postDelayed(watchdog, watchdogMs)
            }
        }

        private fun acquireMulticastLock() {
            runCatching {
                multicastLock?.let { if (!it.isHeld) it.acquire() }
            }
        }

        private fun releaseMulticastLock() {
            runCatching {
                multicastLock?.let { if (it.isHeld) it.release() }
            }
        }

        fun stop() {
            if (!running) return
            running = false
            // 会话/回调只在 handler 线程读写，整段 post 过去避免竞态
            handler.post {
                handler.removeCallbacks(watchdog)
                handler.removeCallbacks(kickLegacy)
                stopSessions()
            }
            serviceName = null
            releaseMulticastLock()
        }

        /** 服务彻底销毁时释放线程；之后不可再 start */
        fun release() {
            running = false
            releaseMulticastLock()
            handler.post {
                handler.removeCallbacks(watchdog)
                handler.removeCallbacks(kickLegacy)
                stopSessions()
                thread.quitSafely()
            }
        }
    }
}
