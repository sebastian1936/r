package com.starcaretech.a.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.HandlerThread
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 发现 adbd 的 mDNS 服务：
 *  - `_adb-tls-pairing._tcp`：无线调试"使用配对码配对设备"时广播（含端口）
 *  - `_adb-tls-connect._tcp`：无线调试主服务（TLS ADB 端口）
 */
object AdbDiscovery {

    const val TYPE_PAIRING = "_adb-tls-pairing._tcp"
    const val TYPE_CONNECT = "_adb-tls-connect._tcp"

    class DiscoveredService(
        val serviceName: String,
        val host: String,
        val port: Int,
        /** TXT 记录（如配对服务的 pn=设备名） */
        val attributes: Map<String, String>
    )

    /**
     * 阻塞扫描第一个匹配服务。必须在非主线程调用。
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
                        if (found == null) latch.countDown()
                    }

                    override fun onServiceResolved(info: NsdServiceInfo?) {
                        if (info == null) return
                        val host = info.host?.hostAddress ?: return
                        val attrs = HashMap<String, String>()
                        // getAttributes() 是 API 33 新增，低版本直接忽略 TXT
                        try {
                            @Suppress("DEPRECATION")
                            val raw = info.attributes
                            raw?.forEach { (k, v) -> if (v != null) attrs[k] = String(v, Charsets.US_ASCII) }
                        } catch (e: NoSuchMethodError) {
                            // Android 11/12 无此方法
                        }
                        synchronized(lock) {
                            if (found == null) {
                                found = DiscoveredService(info.serviceName, host, info.port, attrs)
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
        }
        return synchronized(lock) { found }
    }
}
