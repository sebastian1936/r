package com.starcaretech.a

import ffi.FFI

/**
 * Capture screen,get video and audio,send to rust.
 * Dispatch notifications
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Configuration.ORIENTATION_LANDSCAPE
import android.graphics.Color
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.Surface
import android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT
import android.view.WindowManager
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import java.util.concurrent.Executors
import kotlin.concurrent.thread
import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

const val DEFAULT_NOTIFY_TITLE = "Rust-Desk"
const val DEFAULT_NOTIFY_TEXT = "Service is running"
const val DEFAULT_NOTIFY_ID = 1
const val NOTIFY_ID_OFFSET = 100

// 锁屏保活：AlarmManager 在 Doze 下周期唤醒（exact alarm 在 Doze 下最小约 9 分钟一次）
// 实测小米9锁屏约1小时离线：MIUI 深度待机后 15 分钟唤醒常被推迟，取 Doze 允许的
// 最高频率 9 分钟，缩短离线空窗（每次唤醒仅做一次 UDP 重新注册，耗电可忽略）
const val KEEPALIVE_INTERVAL_MS = 9 * 60_000L
const val KEEPALIVE_ALARM_REQUEST_CODE = 2002
// 信令主动重连最小间隔，避免网络抖动时频繁重启 RendezvousMediator
const val MIN_MEDIATOR_RESTART_INTERVAL_MS = 30_000L

// 录屏授权循环弹窗熔断：会话建立后存活不足 20s 即死算"短命失效"，
// 60s 窗口内连续超过 2 次则停止自动拉起系统确认框，只保留通知供用户手动重试
const val PROJECTION_STABLE_LIFETIME_MS = 20_000L
const val PROJECTION_FUSE_WINDOW_MS = 60_000L
const val PROJECTION_FUSE_MAX_FAILURES = 2

// 帧停滞看门狗：仅在屏幕经历过「息屏→亮屏/解锁」或 VirtualDisplay onResumed
// 之后才开始判定（部分 ROM 静态画面本就不持续推帧，纯无帧不能作为判据）。
// tick 3s；恢复事件后 6s 一帧没来即判定采集链僵死；采集刚（重）建给 8s 宽限；
// 60s 窗口内最多软重建 2 次，仍无帧则判 projection 僵尸、走重新授权。
// 实际时间线（解锁后）：~6s 第一次软重建 → ~17s 第二次 → ~26s 重新授权
const val FRAME_WATCHDOG_INTERVAL_MS = 3_000L
const val FRAME_STALL_THRESHOLD_MS = 6_000L
const val FRAME_STALL_GRACE_MS = 8_000L
const val STALL_REBUILD_WINDOW_MS = 60_000L
const val STALL_REBUILD_MAX = 2
// 无事件静默探针：息屏恢复后这么久仍无帧，且重建链未触发，主动上传诊断（只传一次）
const val SILENT_NO_FRAME_MS = 15_000L
// VirtualDisplay.onResumed 若发生在本次采集启动后该窗口内，视为我们自己
// stop/start 切 surface 引起，不触发停滞判定（防止重连后静态桌面误重建）
const val SELF_RESUME_SUPPRESS_MS = 3_000L

const val MIME_TYPE = MediaFormat.MIMETYPE_VIDEO_VP9

// video const

const val MAX_SCREEN_SIZE = 1200

const val VIDEO_KEY_BIT_RATE = 1024_000
const val VIDEO_KEY_FRAME_RATE = 30

class MainService : Service() {

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustPointerInput(kind: Int, mask: Int, x: Int, y: Int) {
        // turn on screen with LEFT_DOWN when screen off
        if (!powerManager.isInteractive && (kind == 0 || mask == LEFT_DOWN)) {
            if (wakeLock.isHeld) {
                Log.d(logTag, "Turn on Screen, WakeLock release")
                wakeLock.release()
            }
            Log.d(logTag,"Turn on Screen")
            wakeLock.acquire(5000)
        } else {
            when (kind) {
                0 -> { // touch
                    InputService.ctx?.onTouchInput(mask, x, y)
                }
                1 -> { // mouse
                    InputService.ctx?.onMouseInput(mask, x, y)
                }
                else -> {
                }
            }
        }
    }

    @Keep
    @RequiresApi(Build.VERSION_CODES.N)
    fun rustKeyEventInput(input: ByteArray) {
        InputService.ctx?.onKeyEvent(input)
    }

    @Keep
    fun rustGetByName(name: String): String {
        return when (name) {
            "screen_size" -> {
                JSONObject().apply {
                    put("width",SCREEN_INFO.width)
                    put("height",SCREEN_INFO.height)
                    put("scale",SCREEN_INFO.scale)
                }.toString()
            }
            "is_start" -> {
                isStart.toString()
            }
            else -> ""
        }
    }

    @Keep
    fun rustSetByName(name: String, arg1: String, arg2: String) {
        when (name) {
            "add_connection" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val authorized = jsonObject["authorized"] as Boolean
                    val isFileTransfer = jsonObject["is_file_transfer"] as Boolean
                    val type = if (isFileTransfer) {
                        translate("Transfer file")
                    } else {
                        translate("Share screen")
                    }
                    if (authorized) {
                        if (!isFileTransfer) {
                            // 新连接到达：取消尚未执行的迟到 stop_capture，防止误杀新会话
                            keepAliveHandler.removeCallbacks(pendingStopCaptureRunnable)
                            if (!isStart) {
                                startCapture()
                            }
                        }
                        onClientAuthorizedNotification(id, type, username, peerId)
                    } else {
                        loginRequestNotification(id, type, username, peerId)
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "update_voice_call_state" -> {
                try {
                    val jsonObject = JSONObject(arg1)
                    val id = jsonObject["id"] as Int
                    val username = jsonObject["name"] as String
                    val peerId = jsonObject["peer_id"] as String
                    val inVoiceCall = jsonObject["in_voice_call"] as Boolean
                    val incomingVoiceCall = jsonObject["incoming_voice_call"] as Boolean
                    if (!inVoiceCall) {
                        if (incomingVoiceCall) {
                            voiceCallRequestNotification(id, "Voice Call Request", username, peerId)
                        } else {
                            if (!audioRecordHandle.switchOutVoiceCall(mediaProjection)) {
                                Log.e(logTag, "switchOutVoiceCall fail")
                                MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                    "type" to "custom-nook-nocancel-hasclose-error",
                                    "title" to "Voice call",
                                    "text" to "Failed to switch out voice call."))
                            }
                        }
                    } else {
                        if (!audioRecordHandle.switchToVoiceCall(mediaProjection)) {
                            Log.e(logTag, "switchToVoiceCall fail")
                            MainActivity.flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                                "type" to "custom-nook-nocancel-hasclose-error",
                                "title" to "Voice call",
                                "text" to "Failed to switch to voice call."))
                        }
                    }
                } catch (e: JSONException) {
                    e.printStackTrace()
                }
            }
            "stop_capture" -> {
                Log.d(logTag, "from rust:stop_capture")
                // 不立即执行：延迟防抖（见 pendingStopCaptureRunnable 注释）；
                // resumeWanted 推迟到真正执行时再清——被新连接取消则保留
                keepAliveHandler.removeCallbacks(pendingStopCaptureRunnable)
                keepAliveHandler.postDelayed(pendingStopCaptureRunnable, STOP_CAPTURE_DEBOUNCE_MS)
            }
            "half_scale" -> {
                val halfScale = arg1.toBoolean()
                if (isHalfScale != halfScale) {
                    isHalfScale = halfScale
                    updateScreenInfo(resources.configuration.orientation)
                }
                
            }
            else -> {
            }
        }
    }

    private var serviceLooper: Looper? = null
    private var serviceHandler: Handler? = null

    private val powerManager: PowerManager by lazy { applicationContext.getSystemService(Context.POWER_SERVICE) as PowerManager }
    /** 锁屏页（未解锁）期间镜像行为各 ROM 不一，帧看门狗此时不做停滞判定 */
    private val keyguardManager: KeyguardManager? by lazy {
        applicationContext.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
    }
    private val wakeLock: PowerManager.WakeLock by lazy { powerManager.newWakeLock(PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.SCREEN_BRIGHT_WAKE_LOCK, "starcare:wakelock")}

    // "保持屏幕开启"：服务运行期间的常驻亮屏锁（不带 ACQUIRE_CAUSES_WAKEUP：
    // 只阻止屏幕超时熄灭，不主动点亮已关的屏；远程输入时由上面的 wakeLock
    // 临时点亮 5 秒）。释放时机与服务一致
    private var keepAwakeLock: PowerManager.WakeLock? = null

    // ---- 锁屏保活（跟随 MainService 生命周期：服务在=用户期望被控在线）----
    /** 常驻 CPU 锁：锁屏/Doze 下维持 Rust 心跳与信令线程运行 */
    private var cpuWakeLock: PowerManager.WakeLock? = null
    /** WiFi 高性能锁：防止锁屏后 WiFi 进入省电/断连；移动数据机型 acquire 亦无害 */
    private var wifiLock: WifiManager.WifiLock? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    /** registerNetworkCallback 会对当前网络立即回调一次，首次无需触发重连 */
    @Volatile
    private var skipFirstNetworkEvent = true
    @Volatile
    private var lastMediatorRestartMs = 0L
    private val networkRestartRunnable = Runnable { restartRendezvousMediator() }

    /**
     * 迟到 stop_capture 防抖：弱网下连接亚秒级断开重连时，Rust 侧旧连接的
     * remove 与新连接的 add 在不同线程执行且无全局顺序，stop_capture 可能晚于
     * 新连接的 add_connection 到达。延迟执行给新连接一个取消窗口，避免迟到的
     * stop 误杀刚建立的新采集会话——交错释放 surface 会连带触发 VirtualDisplay
     * 异常，被误判成授权失效后接受控制开关会被关闭。
     */
    private val pendingStopCaptureRunnable = Runnable {
        Log.i(logTag, "延迟 stop_capture 到期：期间无新连接，执行停止采集")
        resumeWanted = false
        stopCapture()
    }

    companion object {
        // Rust 侧跨线程轮询 is_start/is_ready、多线程写状态，需保证可见性
        @Volatile
        private var _isReady = false // media permission ready status
        @Volatile
        private var _isStart = false // screen capture start status
        private var _isAudioStart = false // audio capture start status

        /** 迟到 stop_capture 防抖窗口：覆盖弱网下断开→重连的亚秒级竞态 */
        private const val STOP_CAPTURE_DEBOUNCE_MS = 400L

        // 录屏授权失效时的全屏引导通知（区别于常驻前台服务通知 DEFAULT_NOTIFY_ID）
        const val RECOVERY_NOTIFY_ID = 2
        val isReady: Boolean
            get() = _isReady
        val isStart: Boolean
            get() = _isStart
        val isAudioStart: Boolean
            get() = _isAudioStart

        /**
         * 服务当前是否已持有有效的 MediaProjection 会话。
         * 供授权透明页在弹系统确认框前兜底：会话已存活时（残留全屏通知
         * 误触、重复 startActivity）直接放弃，绝不重弹确认框顶掉正在进行
         * 的被控采集（重弹会新建会话并把 App 带到前台）。
         */
        fun hasActiveProjection(): Boolean = aliveInstance?.mediaProjection != null

        /** MainService 进程内存活事实：进程外看门狗（Job/无障碍）据此判断是否需要拉起 */
        @JvmStatic
        @Volatile
        var isServiceAlive: Boolean = false
            private set

        @JvmStatic
        fun markServiceAlive(alive: Boolean) {
            isServiceAlive = alive
            if (!alive) aliveInstance = null
        }

        /** 当前进程内的服务实例（onCreate 置位，onDestroy 清空），供进程外
         *  周期锚点（JobScheduler 看门狗）在不经过 onStartCommand、不受
         *  Android12+ 后台启动 FGS 限制的情况下直接补一次保活心跳 */
        @Volatile
        private var aliveInstance: MainService? = null

        /**
         * 进程外锚点调用：服务存活则在实例上补保活心跳（续命 alarm +
         * 重连信令），返回 true；不存活返回 false，调用方应转而拉起服务。
         */
        @JvmStatic
        fun pokeKeepAliveIfAlive(): Boolean {
            val s = aliveInstance ?: return false
            s.handleKeepAliveTick("external-anchor")
            return true
        }

        /**
         * 全局录屏授权请求闸门：同一时刻只允许一个系统确认框在途。
         * 看门狗/亮屏 receiver/Activity 重建/多次 startId 等多个触发源，
         * 若不加串行会叠出多个透明 Activity（日志实锤"点一个又一个"），
         * 多授权实例互顶会话 + 并发 MediaCodec 最终 native 崩溃。
         */
        @Volatile
        private var projectionRequestInFlight = false
        private val projectionGateLock = Any()
        private val projectionGateHandler = Handler(Looper.getMainLooper())
        private const val PROJECTION_REQUEST_TIMEOUT_MS = 45_000L
        private val projectionGateTimeout = Runnable {
            // Activity 被系统杀死/回调丢失时兜底释放闸门，避免永久锁死
            synchronized(projectionGateLock) { projectionRequestInFlight = false }
        }

        /** 尝试占位授权请求；已有请求在途时返回 false（调用方应放弃本次弹窗） */
        fun beginProjectionRequest(): Boolean = synchronized(projectionGateLock) {
            if (projectionRequestInFlight) {
                false
            } else {
                projectionRequestInFlight = true
                projectionGateHandler.removeCallbacks(projectionGateTimeout)
                projectionGateHandler.postDelayed(
                    projectionGateTimeout,
                    PROJECTION_REQUEST_TIMEOUT_MS
                )
                true
            }
        }

        /** 授权请求结束（成功回传/用户取消/失败），释放闸门供下一次请求使用 */
        fun endProjectionRequest() = synchronized(projectionGateLock) {
            projectionGateHandler.removeCallbacks(projectionGateTimeout)
            projectionRequestInFlight = false
        }

        // ---- 保持屏幕开启（由 Dart 设置项驱动，无需 Activity/悬浮窗，后台也生效）----
        @JvmStatic
        @Volatile
        private var pendingKeepScreenOn: Boolean = false

        private var keepAwakeInstance: MainService? = null

        /** Dart 侧设置变化/服务状态变化时调用；服务未启动时记录意愿，启动即生效 */
        @JvmStatic
        fun setKeepScreenOn(on: Boolean) {
            pendingKeepScreenOn = on
            keepAwakeInstance?.applyKeepScreenOn(on)
        }
    }

    private val logTag = "LOG_SERVICE"
    private val useVP9 = false
    private val binder = LocalBinder()

    private var reuseVirtualDisplay = Build.VERSION.SDK_INT > 33

    // video
    private var mediaProjection: MediaProjection? = null
    private var projectionCallback: MediaProjection.Callback? = null

    /** 服务主动销毁标记：为 true 时忽略系统迟到的 MediaProjection.onStop 回调 */
    @Volatile
    private var serviceDestroyed = false

    /**
     * 采集是否因录屏会话失效被打断（而不是客户端主动断开）。
     * 录屏重新就绪后据此自动续上采集，主控端无需重连、无需开关服务
     */
    @Volatile
    private var resumeWanted = false

    /** 当前 MediaProjection 会话建立时刻；区分"正常收回"与"建立即死" */
    @Volatile
    private var projectionEstablishedMs = 0L
    /** 时间窗口内连续短命失效次数与窗口起点（熔断自动弹窗用） */
    @Volatile
    private var shortLivedProjectionFailures = 0
    @Volatile
    private var projectionFailWindowStartMs = 0L
    /** 上次"连接时无录屏会话"引导时间（节流） */
    @Volatile
    private var lastCapturePromptMs = 0L
    /** 上次自动拉起授权页时间（3s 去抖，多触发源只发一次） */
    @Volatile
    private var lastAutoPromptMs = 0L

    /**
     * 亮屏/解锁监听：国产 ROM 普遍在锁屏后停止 MediaProjection 会话，
     * 亮屏时先用缓存 token 静默恢复（targetSdk33 下同开机周期 token 可复用），
     * 恢复成功则自动续采集；token 也被废时刷新全屏恢复通知，
     * 系统确认框由本应用的无障碍服务自动点击（见 InputService）
     */
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> handleScreenStateChanged("broadcast_screen_on")
                Intent.ACTION_USER_PRESENT -> handleScreenStateChanged("broadcast_user_present")
            }
        }
    }

    private fun handleScreenStateChanged(source: String = "broadcast") {
        // 用户主动关服后，服务实例可能因 MainActivity 仍 bind 而存活、onDestroy
        // 不执行，本 receiver 也未注销。此时亮屏/解锁绝不能恢复录屏或重连信令，
        // 否则 FFI.startService() 会清掉 stop-service 标记并重新注册上线，
        // 表现为"关闭接受控制后主控依然能连上"。
        if (serviceDestroyed) {
            Log.i(logTag, "服务已关闭，忽略亮屏/解锁事件（不恢复录屏/信令）")
            return
        }
        // 亮屏/解锁后开始等帧：部分 ROM（红米 Android16 实测）解锁后
        // projection 未失效但帧流不恢复，由帧看门狗在阈值后软重建采集链。
        // 留痕广播到达事实——若云端只见 poll flip 不见 broadcast，即 ROM 掐广播
        captureDiag(
            "screen event src=$source isStart=$isStart hasProjection=${mediaProjection != null} " +
                "interactive=${powerManager.isInteractive} locked=${keyguardManager?.isKeyguardLocked}"
        )
        if (isStart && mediaProjection != null && powerManager.isInteractive) {
            // 停在锁屏页（SCREEN_ON 但未解锁）时先不判，等 USER_PRESENT/轮询解锁翻转
            if (keyguardManager?.isKeyguardLocked != true) {
                beginAwaitingFrames(source)
            }
        }
        if (!_isReady) {
            Log.i(logTag, "亮屏/解锁：尝试用缓存授权静默恢复录屏")
            if (tryRestoreMediaProjection()) {
                Log.i(logTag, "缓存授权静默恢复成功")
            } else {
                Log.w(logTag, "缓存授权已失效，刷新录屏恢复通知等待确认（前台时自动弹一次）")
                // 统一走引导入口（前台门控 + 在途闸门 + 熔断保护）；
                // App 不在前台/锁屏时只更新通知，不会连环弹确认框
                requestMediaProjectionWithNotice()
                return
            }
        }
        if (resumeWanted && !isStart && mediaProjection != null) {
            Log.i(logTag, "录屏已恢复，自动续上屏幕采集")
            resumeWanted = false
            startCapture()
        }
        // 锁屏期间信令长连可能已被挂起中断，亮屏网络恢复后主动重连（带 30s 去抖）
        restartRendezvousMediator()
    }

    // ===================== 锁屏保活 =====================
    // 现象（小米9/Android11）：锁屏一段时间后主控提示离线，进程并未死亡，亮屏（如来电）即恢复。
    // 根因：Doze/MIUI 锁屏后挂起 CPU 与 WiFi，Rust 信令长连心跳中断被判离线。
    // 组合手段：常驻 PARTIAL_WAKE_LOCK + WifiLock + 网络恢复主动重连 + AlarmManager 周期唤醒。

    private fun initKeepAlive() {
        // 1) CPU 常驻锁（无超时，服务销毁/进程死亡时自动释放）
        runCatching {
            if (cpuWakeLock == null) {
                cpuWakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK, "starcare:cpu-keepalive"
                ).apply { setReferenceCounted(false) }
            }
            if (cpuWakeLock?.isHeld != true) {
                cpuWakeLock?.acquire()
                Log.i(logTag, "PARTIAL_WAKE_LOCK acquired")
            }
        }.onFailure { Log.w(logTag, "acquire PARTIAL_WAKE_LOCK fail", it) }

        // 2) WiFi 高性能锁
        runCatching {
            if (wifiLock == null) {
                val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_HIGH_PERF, "starcare:wifi-keepalive"
                ).apply { setReferenceCounted(false) }
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
                Log.i(logTag, "WifiLock acquired")
            }
        }.onFailure { Log.w(logTag, "acquire WifiLock fail", it) }

        // 3) 网络恢复主动重连（API26+；低版本靠周期 alarm 兜底）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && networkCallback == null) {
            registerNetworkCallback()
        }

        // 4) Doze 周期唤醒
        scheduleKeepAliveTick(KEEPALIVE_INTERVAL_MS)
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (skipFirstNetworkEvent) {
                    skipFirstNetworkEvent = false
                    return
                }
                Log.i(logTag, "网络恢复，2s 去抖后触发信令重连")
                keepAliveHandler.removeCallbacks(networkRestartRunnable)
                keepAliveHandler.postDelayed(networkRestartRunnable, 2000)
            }
        }
        runCatching { cm.registerNetworkCallback(request, cb) }
            .onSuccess { networkCallback = cb }
            .onFailure { Log.w(logTag, "registerNetworkCallback fail", it) }
    }

    /** 触发 Rust 端 RendezvousMediator 重新注册信令服务器（幂等，带间隔去抖） */
    private fun restartRendezvousMediator() {
        // 纵深防御：用户关服后任何保活/网络/亮屏路径都不得重新拉起信令。
        // FFI.startService() 对应 Rust main_start_service()，会清空
        // stop-service 选项并重新向 hbbs 注册，使设备在开关关闭状态下重新上线。
        if (serviceDestroyed) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastMediatorRestartMs < MIN_MEDIATOR_RESTART_INTERVAL_MS) {
            Log.d(logTag, "信令重连间隔去抖，跳过")
            return
        }
        lastMediatorRestartMs = now
        Log.i(logTag, "触发 RendezvousMediator 重连")
        runCatching { FFI.startService() }
            .onFailure { Log.w(logTag, "FFI.startService fail", it) }
    }

    private fun keepAlivePendingIntent(): PendingIntent {
        val intent = Intent(this, MainService::class.java).apply { action = ACT_KEEPALIVE_TICK }
        val flags = FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) FLAG_IMMUTABLE else 0)
        // getForegroundService 为 API26+；低版本回退 getService
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            PendingIntent.getForegroundService(this, KEEPALIVE_ALARM_REQUEST_CODE, intent, flags)
        } else {
            PendingIntent.getService(this, KEEPALIVE_ALARM_REQUEST_CODE, intent, flags)
        }
    }

    @SuppressLint("MissingPermission")
    private fun scheduleKeepAliveTick(delayMs: Long) {
        val am = getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = SystemClock.elapsedRealtime() + delayMs
        val pi = keepAlivePendingIntent()
        // API22 无 Doze，普通唤醒闹钟即可
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            runCatching { am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) }
            return
        }
        runCatching {
            // API31+ 精确闹钟权限可能被用户撤销，无权限时退化非精确（仍可在 Doze 维护窗口送达）
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }.onFailure {
            Log.w(logTag, "schedule exact keepalive alarm fail, fallback to inexact", it)
            runCatching {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        }
    }

    /**
     * 一次保活心跳（alarm 触发 / Job 看门狗锚点直接调用）：
     * 补前台通知与保活锁、续下一次 alarm（alarm 链若曾因后台 FGS 启动被拒
     * 而断链，只要任一锚点到达就能自愈）、主动重连信令服务器。
     * 可在任意线程调用（Job 在工作线程）。
     */
    fun handleKeepAliveTick(from: String) {
        if (serviceDestroyed) return
        Log.i(logTag, "keepalive tick from=$from")
        runCatching { createForegroundNotification() }
        initKeepAlive()
        restartRendezvousMediator()
    }

    private fun releaseKeepAlive() {
        keepAliveHandler.removeCallbacks(networkRestartRunnable)
        // 服务销毁/释放时撤掉尚未执行的迟到 stop（destroy 自身已同步 stopCapture）
        keepAliveHandler.removeCallbacks(pendingStopCaptureRunnable)
        runCatching { cpuWakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            networkCallback?.let { cb ->
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                runCatching { cm?.unregisterNetworkCallback(cb) }
            }
        }
        networkCallback = null
        runCatching {
            (getSystemService(Context.ALARM_SERVICE) as? AlarmManager)?.cancel(keepAlivePendingIntent())
        }
    }

    private var surface: Surface? = null
    private val sendVP9Thread = Executors.newSingleThreadExecutor()
    private var videoEncoder: MediaCodec? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    // ===================== 帧停滞看门狗 =====================
    // 僵尸会话场景（红米 Turbo 5 Max / Android 16 实测）：锁屏再亮屏后
    // MediaProjection 未被收回（系统"正在录屏"通知还在、onStop 不回调、
    // createVirtualDisplay 也不抛异常），但 VirtualDisplay/ImageReader 已
    // 停止供帧，主控一直"等待画面传输"，本应用既检测不到也不提示。
    /** 最近一次收到屏幕帧的时刻（onImageAvailable 实际取到 image 时更新，仅用于日志） */
    @Volatile
    private var lastFrameElapsedMs = 0L
    /** 采集进程累计收到的帧数（诊断用：区分"帧没到采集链"与"帧到了但下游断"） */
    @Volatile
    private var frameTotalCounter = 0L
    /** 本次采集启动时刻，启动初期给宽限，避免冷启动慢被误判 */
    @Volatile
    private var captureStartedMs = 0L
    /**
     * 非 0 表示正在等待屏幕恢复后的首批帧（值为等待起点）。
     * 由亮屏/解锁广播、VirtualDisplay.onResumed、或看门狗轮询到的屏幕状态翻转置位，
     * 收到任意帧清零；为 0 时不做重建判定——静态画面无帧属正常，避免误杀。
     */
    @Volatile
    private var awaitingFramesSinceMs = 0L
    /** 时间窗内僵尸重建次数与窗口起点（重建多次仍无帧则升级为重新授权） */
    @Volatile
    private var stallRebuildCount = 0
    @Volatile
    private var stallWindowStartMs = 0L

    // —— 不依赖系统广播/回调的轮询兜底（国产 ROM 可能掐掉 SCREEN_ON/USER_PRESENT
    // 或不回调 VirtualDisplay.onResumed）：看门狗每 tick 直接读系统状态比对翻转 ——
    @Volatile
    private var prevInteractive: Boolean = true
    @Volatile
    private var prevKeyguardLocked: Boolean = false
    /** 本次采集期间是否观察到息屏（tick 轮询到 interactive=false 即置位，startCapture 复位）。
     *  仅用于给"无事件静默探针"门控，保证从未息屏的纯静态桌面不会被误报 */
    @Volatile
    private var screenOffSeenDuringCapture = false
    /** 同一次无帧现场静默上报只发一次，帧流恢复后复位 */
    @Volatile
    private var silentProbeFired = false

    private val frameStallCheckOnce = Runnable { runCatching { checkFrameStall() } }

    private val frameWatchdogRunnable = object : Runnable {
        override fun run() {
            // 顺序：先轮询屏幕翻转（可能置等帧状态），再跑停滞判定与静默探针
            runCatching { pollScreenStateFlip() }
            runCatching { checkFrameStall() }
            runCatching { silentNoFrameProbe() }
            serviceHandler?.postDelayed(this, FRAME_WATCHDOG_INTERVAL_MS)
        }
    }

    /** 统一置位"等待恢复后首批帧"并留痕（来源用于诊断哪条触发链生效） */
    private fun beginAwaitingFrames(source: String) {
        if (!isStart || mediaProjection == null) return
        val now = SystemClock.elapsedRealtime()
        val already = awaitingFramesSinceMs != 0L
        awaitingFramesSinceMs = now
        captureDiag(
            "await begin src=$source already=$already interactive=${powerManager.isInteractive} " +
                "locked=${keyguardManager?.isKeyguardLocked} totalFrames=$frameTotalCounter " +
                "lastFrameAge=${now - lastFrameElapsedMs}ms"
        )
        serviceHandler?.let { h ->
            h.removeCallbacks(frameStallCheckOnce)
            h.postDelayed(frameStallCheckOnce, 3_000L)
        }
    }

    /** 采集链诊断：写 AdbAuthManager 诊断缓冲（随任意一次 DiagUploader 上报带上云端） */
    private fun captureDiag(text: String) {
        runCatching {
            com.starcaretech.a.adb.AdbAuthManager.trace(applicationContext, "capture: $text")
        }
    }

    /** 采集链异常：立即静默上传诊断现场（不等 ADB 授权失败触发点） */
    private fun reportCaptureStall(reason: String) {
        runCatching {
            com.starcaretech.a.adb.DiagUploader.reportAsync(
                applicationContext, "capture_stall", reason
            )
        }
    }

    /**
     * 轮询屏幕交互/锁屏状态翻转。不依赖广播：即使 ROM 掐掉 SCREEN_ON/USER_PRESENT，
     * 3s 粒度的轮询也能在亮屏/解锁后识别恢复并进入等帧判定。
     */
    private fun pollScreenStateFlip() {
        if (serviceDestroyed) return
        val interactive = powerManager.isInteractive
        val locked = keyguardManager?.isKeyguardLocked == true
        if (!interactive) screenOffSeenDuringCapture = true
        if (isStart && mediaProjection != null) {
            if (!prevInteractive && interactive) {
                captureDiag("poll flip: screen off->on（广播可能被 ROM 限制，轮询兜底生效）")
                if (!locked) beginAwaitingFrames("poll_screen_on")
            }
            if (prevKeyguardLocked && !locked && interactive) {
                captureDiag("poll flip: keyguard locked->unlocked")
                beginAwaitingFrames("poll_unlock")
            }
        }
        prevInteractive = interactive
        prevKeyguardLocked = locked
    }

    /**
     * 无事件静默探针（只诊断、不重建）：本次采集期间观察到过息屏，亮屏解锁后
     * 超过 [SILENT_NO_FRAME_MS] 仍一帧未来（且系统广播/onResumed 触发链因
     * ROM 限制等原因没走到重建），主动上传一次现场。覆盖"看门狗活着但没动作"。
     * 注意：帧持续到达 Kotlin 但主控无画面（下游 Rust 编码/传输嫌疑）不触发本探针，
     * 那种情况 lastFrameAge 一直很小，需后续在 Rust 侧加编码帧时间戳才能确诊。
     */
    private fun silentNoFrameProbe() {
        if (silentProbeFired) return
        if (!isStart || mediaProjection == null) return
        if (!powerManager.isInteractive) return
        if (keyguardManager?.isKeyguardLocked == true) return
        val now = SystemClock.elapsedRealtime()
        if (!screenOffSeenDuringCapture) return
        if (now - captureStartedMs < SILENT_NO_FRAME_MS) return
        val lastAge = now - lastFrameElapsedMs
        if (lastFrameElapsedMs == 0L || lastAge > SILENT_NO_FRAME_MS) {
            silentProbeFired = true
            captureDiag(
                "silent probe: 息屏恢复后 ${lastAge}ms 无帧（awaiting=${awaitingFramesSinceMs != 0L}）" +
                    "totalFrames=$frameTotalCounter —— 帧未到采集链且重建链未触发"
            )
            reportCaptureStall("no_frame_silent")
        }
    }

    /**
     * VirtualDisplay 生命周期回调（onPaused/onResumed 仅 API33+ 会回调，
     * 低版本注册无副作用）。部分 ROM 锁屏暂停镜像、解锁后不真正恢复，
     * onResumed 只代表系统"意图恢复"，必须以帧流是否真的回来为准。
     */
    private val virtualDisplayCallback = object : VirtualDisplay.Callback() {
        override fun onPaused() {
            Log.i(logTag, "VirtualDisplay onPaused（通常为锁屏，息屏期间不做停滞判定）")
            captureDiag("vd onPaused")
        }

        override fun onResumed() {
            // 客户端断开/重连时 stopCapture/startCapture 会 setSurface(null)->setSurface(s)，
            // 同样触发 onResumed；这种"我们自己切 surface"距本次采集启动很近，
            // 不能置等帧状态（重连后静态桌面本就可能长时间无帧，会误判重建）。
            // 只有采集已稳定运行后、系统侧（锁屏）自发的 resume 才需要校验。
            val sinceStart = SystemClock.elapsedRealtime() - captureStartedMs
            val selfCaused = sinceStart < SELF_RESUME_SUPPRESS_MS
            Log.i(logTag, "VirtualDisplay onResumed（sinceStart=${sinceStart}ms selfCaused=$selfCaused）")
            captureDiag("vd onResumed sinceStart=${sinceStart}ms selfCaused=$selfCaused")
            if (!selfCaused && isStart && mediaProjection != null
                && powerManager.isInteractive && keyguardManager?.isKeyguardLocked != true
            ) {
                beginAwaitingFrames("vd_resumed")
            }
        }
    }

    // audio
    private val audioRecordHandle = AudioRecordHandle(this, { isStart }, { isAudioStart })

    // notification
    private lateinit var notificationManager: NotificationManager
    private lateinit var notificationChannel: String

    /**
     * 每条通知必须使用全新 Builder。
     * NotificationCompat.Builder 设置过的字段（尤其 fullScreenIntent/category）
     * 在 build() 之后不会清除：曾因全服务共用一个 builder，录屏恢复通知的
     * 全屏 Intent（指向授权透明页 + CATEGORY_CALL）残留到后续所有通知上，
     * 被控连接通知在 MIUI/OriginOS 被系统按来电类通知直接展开，每次被远程
     * 都把 RustDesk 强行拉到前台。
     */
    private fun newNotificationBuilder(): NotificationCompat.Builder =
        NotificationCompat.Builder(this, notificationChannel)
            // smallIcon 为通知必备项：连接类通知此前靠共享 builder 残留的
            // 图标"继承"，独立 builder 必须显式设置，否则 notify 不显示
            .setSmallIcon(R.mipmap.ic_stat_logo)

    override fun onCreate() {
        super.onCreate()
        StartWatchdog.stage("MainService.onCreate: enter")
        // 服务存活事实 + 用户期望在线：进程外看门狗（Job/无障碍/开机）据此拉起
        markServiceAlive(true)
        aliveInstance = this
        WatchdogScheduler.setServiceWanted(applicationContext, true)
        Log.d(logTag,"MainService onCreate, sdk int:${Build.VERSION.SDK_INT} reuseVirtualDisplay:$reuseVirtualDisplay")
        StartWatchdog.stage("MainService: FFI.init 前")
        FFI.init(this)
        StartWatchdog.stage("MainService: FFI.init 后")
        HandlerThread("Service", Process.THREAD_PRIORITY_BACKGROUND).apply {
            start()
            serviceLooper = looper
            serviceHandler = Handler(looper)
        }
        updateScreenInfo(resources.configuration.orientation)
        initNotification()

        // keep the config dir same with flutter
        val prefs = applicationContext.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
        val configPath = prefs.getString(KEY_APP_DIR_CONFIG_PATH, "") ?: ""
        StartWatchdog.stage("MainService: FFI.startServer 前")
        FFI.startServer(configPath, "")
        StartWatchdog.stage("MainService: FFI.startServer 后")

        createForegroundNotification()

        // SCREEN_ON / USER_PRESENT 只能动态注册
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenStateReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenStateReceiver, filter)
        }

        // 锁屏保活：CPU/WiFi 锁 + 网络恢复重连 + Doze 周期唤醒
        initKeepAlive()

        // 保持屏幕开启：服务启动时按设置项恢复常亮状态
        keepAwakeInstance = this
        applyKeepScreenOn(pendingKeepScreenOn)

        // 防电诈：系统通话期间锁定被控输入（AudioManager 轮询，无需权限）
        CallStateMonitor.start(applicationContext)
        StartWatchdog.stage("MainService.onCreate: done")
    }

    override fun onDestroy() {
        // 静态状态复位：否则系统销毁本实例后（如绑定断开），新实例的 mediaProjection 为 null
        // 但 isReady 仍为 true，远程连接进来时 startCapture 会静默失败
        _isReady = false
        _isStart = false
        markServiceAlive(false)
        // 注意：这里不清 KEY_SERVICE_WANTED/不取消看门狗——
        // 用户主动关服走 destroy()；若是系统异常销毁，看门狗应把服务拉回来
        serviceDestroyed = true
        projectionCallback?.let { cb -> runCatching { mediaProjection?.unregisterCallback(cb) } }
        projectionCallback = null
        mediaProjection = null
        checkMediaPermission()
        runCatching { unregisterReceiver(screenStateReceiver) }
        releaseKeepAlive()
        applyKeepScreenOn(false)
        keepAwakeInstance = null
        stopService(Intent(this, FloatingWindowService::class.java))
        // 防电诈：注销通话监听并恢复输入锁
        CallStateMonitor.stop()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    fun applyKeepScreenOn(on: Boolean) {
        runCatching {
            if (on) {
                if (keepAwakeLock == null) {
                    keepAwakeLock = powerManager.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "starcare:keep-screen-on"
                    )
                }
                if (keepAwakeLock?.isHeld != true) {
                    keepAwakeLock?.acquire()
                    Log.i(logTag, "保持屏幕开启：亮屏锁已持有")
                }
            } else {
                if (keepAwakeLock?.isHeld == true) {
                    keepAwakeLock?.release()
                    Log.i(logTag, "保持屏幕开启：亮屏锁已释放")
                }
            }
        }.onFailure { Log.w(logTag, "切换保持屏幕开启失败 on=$on", it) }
    }

    private var isHalfScale: Boolean? = null;
    private fun updateScreenInfo(orientation: Int) {
        var w: Int
        var h: Int
        var dpi: Int
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val m = windowManager.maximumWindowMetrics
            w = m.bounds.width()
            h = m.bounds.height()
            dpi = resources.configuration.densityDpi
        } else {
            val dm = DisplayMetrics()
            windowManager.defaultDisplay.getRealMetrics(dm)
            w = dm.widthPixels
            h = dm.heightPixels
            dpi = dm.densityDpi
        }

        val max = max(w,h)
        val min = min(w,h)
        if (orientation == ORIENTATION_LANDSCAPE) {
            w = max
            h = min
        } else {
            w = min
            h = max
        }
        Log.d(logTag,"updateScreenInfo:w:$w,h:$h")
        var scale = 1
        if (w != 0 && h != 0) {
            if (isHalfScale == true && (w > MAX_SCREEN_SIZE || h > MAX_SCREEN_SIZE)) {
                scale = 2
                w /= scale
                h /= scale
                dpi /= scale
            }
            if (SCREEN_INFO.width != w) {
                SCREEN_INFO.width = w
                SCREEN_INFO.height = h
                SCREEN_INFO.scale = scale
                SCREEN_INFO.dpi = dpi
                if (isStart) {
                    stopCapture()
                    FFI.refreshScreen()
                    startCapture()
                } else {
                    FFI.refreshScreen()
                }
            }

        }
    }

    override fun onBind(intent: Intent): IBinder {
        Log.d(logTag, "service onBind")
        return binder
    }

    inner class LocalBinder : Binder() {
        init {
            Log.d(logTag, "LocalBinder init")
        }

        fun getService(): MainService = this@MainService
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("whichService", "this service: ${Thread.currentThread()}")
        com.starcaretech.a.adb.AdbAuthManager.trace(
            applicationContext, "MainService.onStartCommand action=${intent?.action} startId=$startId")
        super.onStartCommand(intent, flags, startId)
        // 关键修复：MainActivity 长期 bindService，stopSelf() 后服务实例可能
        // 因绑定仍存活，destroy() 里置的 serviceDestroyed=true 会残留——
        // 用户再次开启服务时走同一实例，promptProjectionRecovery 等恢复
        // 路径全部被 serviceDestroyed 短路，表现为"服务开了但录屏打不开"。
        // 只要收到新的启动类 action，就视为服务被重新启用，复位销毁态。
        if (intent?.action == ACT_INIT_MEDIA_PROJECTION_AND_SERVICE ||
            intent?.action == ACT_TRY_RESTORE_MEDIA_PROJECTION
        ) {
            if (serviceDestroyed) {
                Log.i(logTag, "服务在绑定保活中被重新启用，复位销毁标记")
            }
            serviceDestroyed = false
            WatchdogScheduler.setServiceWanted(applicationContext, true)
        }
        when (intent?.action) {
            ACT_INIT_MEDIA_PROJECTION_AND_SERVICE -> {
                createForegroundNotification()

                if (intent.getBooleanExtra(EXT_INIT_FROM_BOOT, false)) {
                    FFI.startService()
                }
                Log.d(logTag, "service starting: ${startId}:${Thread.currentThread()}")
                val mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

                intent.getParcelableExtra<Intent>(EXT_MEDIA_PROJECTION_RES_INTENT)?.let { token ->
                    // 用户刚完成确认：缓存 token 供后续静默恢复
                    if (_isReady && mediaProjection != null) {
                        // 多实例透明 Activity 曾各自回传一次结果（service starting 2~9），
                        // 重复 applyProjection 会互顶会话并并发创建 MediaCodec（-38 崩溃链），
                        // 已就绪时直接丢弃迟到的重复授权
                        Log.i(logTag, "录屏已就绪，忽略重复的授权结果回传")
                        com.starcaretech.a.adb.AdbAuthManager.trace(
                            applicationContext, "录屏 ACT_INIT：已就绪，忽略重复授权")
                        endProjectionRequest()
                    } else {
                        com.starcaretech.a.adb.AdbAuthManager.trace(
                            applicationContext, "录屏 ACT_INIT：applyProjection 前")
                        val projOk = applyProjection(mediaProjectionManager, token)
                        com.starcaretech.a.adb.AdbAuthManager.trace(
                            applicationContext, "录屏 ACT_INIT：applyProjection=$projOk")
                        if (!projOk) {
                            Log.w(logTag, "新鲜授权 token 也无法建立 MediaProjection，回退重新请求")
                            endProjectionRequest()
                            // 同步失败同样计入熔断，避免异常 ROM 下"点允许→秒失败→再弹"的死循环
                            promptProjectionRecovery(recordProjectionFailure())
                        } else {
                            MediaProjectionTokenStore.save(this, token)
                            _isReady = true
                            // 用户完成了一次有效授权：历史短命失败计数全部清零，
                            // 否则熔断后手动成功一次，下次单次抖动又会立即熔断
                            shortLivedProjectionFailures = 0
                            endProjectionRequest()
                            // checkMediaPermission 内部会撤下恢复通知并同步 Dart 状态
                            checkMediaPermission()
                            com.starcaretech.a.adb.AdbAuthManager.trace(
                                applicationContext, "录屏 ACT_INIT：授权就绪 checkMediaPermission 完成")
                            resumeCaptureIfWanted()
                        }
                    }
                } ?: let {
                    Log.d(logTag, "getParcelableExtra intent null, try restore then request")
                    // 开机/无 token 启动：先试缓存静默恢复，失败再弹窗
                    if (!tryRestoreMediaProjection(mediaProjectionManager)) {
                        requestMediaProjectionWithNotice()
                    }
                }
            }
            ACT_TRY_RESTORE_MEDIA_PROJECTION -> {
                // App 在前台（init_service）触发：先静默恢复，失败再走确认框
                createForegroundNotification()
                val mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                if (!tryRestoreMediaProjection(mediaProjectionManager)) {
                    requestMediaProjectionWithNotice()
                }
            }
            ACT_WATCHDOG_RESTART -> {
                // 进程外看门狗（JobScheduler/无障碍 onServiceConnected）拉起：
                // onCreate 已完成信令初始化、前台通知与保活锁，服务活着即达成目的。
                // 锁屏被杀恢复场景绝不在此主动弹录屏框——多触发源叠加会形成弹窗风暴；
                // 录屏恢复只走两条路：主控连接触发 startCapture 引导，或用户点恢复通知。
                Log.i(logTag, "watchdog restart：信令已恢复，录屏等待连接或用户确认")
                runCatching { createForegroundNotification() }
            }
            ACT_KEEPALIVE_TICK -> {
                // Doze 周期唤醒（也可能在进程/服务被回收后由 alarm 重建投递）
                handleKeepAliveTick("alarm")
            }
        }
        return START_NOT_STICKY // don't use sticky (auto restart), the new service (from auto restart) will lose control
    }

    /**
     * 用户在最近任务列表划掉本应用：视为"主动退出"，彻底停止被控服务。
     * - 停 Rust 信令注册（真正从服务器离线），再 destroy Android 前台服务壳；
     * - destroy() 会清 KEY_SERVICE_WANTED 并取消看门狗/开机恢复，
     *   不会在 15 分钟后或重启手机后被自动拉起；InputService 巡检与 Job
     *   锚点同样先查该标记，不会把服务拉回，故无障碍无需关闭。
     * - 刻意不 disableSelf()：MIUI Android12 实测 disableSelf 后再经
     *   WRITE_SECURE_SETTINGS/ shell 把组件写回名单，系统经常只恢复开关
     *   显示而不真正绑定服务——表现为重开后录屏授权框无法自动确认、连上
     *   只能看不能控。保留无障碍绑定，重开即"already"，且 MainService
     *   已停、wanted=false，无障碍空转没有任何被控能力。
     * 注意区分：系统在后台因内存杀进程不会回调本方法，那条路径看门狗照常兜底。
     * 个别国产 ROM 上滑划任务直接杀进程、不保证回调，属尽力而为。
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(logTag, "最近任务被划掉：用户主动退出，停止被控服务（保留无障碍绑定）")
        runCatching { FFI.stopService() }
        destroy()
        super.onTaskRemoved(rootIntent)
    }

    /**
     * 用授权结果 Intent 建立 MediaProjection 并注册掉线回调。
     * @return false 表示 token 已失效（SecurityException/IllegalStateException）
     */
    @Synchronized
    private fun applyProjection(manager: MediaProjectionManager, token: Intent): Boolean {
        // 建立新会话前必须彻底摘掉旧会话：旧会话残留时，新会话建立会顶掉旧会话，
        // 旧 callback 迟到的 onStop 又会把新会话误判为失效（崩溃恢复后无限弹框的根因）。
        // 先 unregister 再 stop，避免主动 stop 触发自己 callback 的 onStop 重入。
        projectionCallback?.let { cb -> runCatching { mediaProjection?.unregisterCallback(cb) } }
        projectionCallback = null
        runCatching { mediaProjection?.stop() }
        mediaProjection = null

        return try {
            val mp = manager.getMediaProjection(Activity.RESULT_OK, token)
            if (mp == null) {
                Log.w(logTag, "getMediaProjection 返回 null")
                false
            } else {
                val cb = object : MediaProjection.Callback() {
                    override fun onStop() {
                        // 只处理当前实例的回调；旧会话迟到的 onStop 直接忽略
                        if (mediaProjection !== mp) {
                            Log.i(logTag, "忽略旧 MediaProjection 实例的迟到 onStop")
                            return
                        }
                        Log.w(logTag, "MediaProjection onStop：会话被系统收回")
                        onProjectionInvalid()
                    }
                }
                mp.registerCallback(cb, Handler(Looper.getMainLooper()))
                projectionCallback = cb
                mediaProjection = mp
                projectionEstablishedMs = SystemClock.elapsedRealtime()
                true
            }
        } catch (e: SecurityException) {
            Log.w(logTag, "getMediaProjection SecurityException，授权 token 已失效", e)
            false
        } catch (e: IllegalStateException) {
            Log.w(logTag, "getMediaProjection IllegalStateException，授权 token 已失效", e)
            false
        }
    }

    /** 尝试用缓存的授权 Intent 静默恢复；成功返回 true，失败（无缓存/失效）返回 false */
    @Synchronized
    fun tryRestoreMediaProjection(
        manager: MediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    ): Boolean {
        if (mediaProjection != null) return true
        val token = MediaProjectionTokenStore.load(this) ?: return false
        if (!applyProjection(manager, token)) {
            MediaProjectionTokenStore.clear(this)
            return false
        }
        Log.i(logTag, "MediaProjection 已用缓存授权静默恢复")
        _isReady = true
        checkMediaPermission()
        resumeCaptureIfWanted()
        return true
    }

    /** 录屏重新就绪后，若采集是被系统打断的则自动续上（主控无需重连） */
    private fun resumeCaptureIfWanted() {
        if (resumeWanted && !isStart && mediaProjection != null) {
            Log.i(logTag, "自动续上屏幕采集")
            resumeWanted = false
            startCapture()
        }
    }

    /**
     * 帧停滞检测（serviceHandler 周期调用 / 亮屏、VirtualDisplay.onResumed 后追加调用）。
     *
     * 只在以下条件全部满足时判定采集链僵死：
     *  - 采集进行中（isStart）且 projection 对象还在；
     *  - 屏幕处于交互态（息屏/AOD 不出帧是正常的）；
     *  - 经历过亮屏/解锁/onResumed 事件且之后一帧都没收到（awaitingFramesSinceMs≠0）；
     *  - 已超过启动宽限与无帧阈值。
     * 纯静态画面（从未息屏）不会触发，避免把"画面不动"误判成故障。
     *
     * 处置分两级：先强制释放并重建 VirtualDisplay/ImageReader（复用现存 projection，
     * 无授权弹窗，主控连接不断）；窗口内重建 [STALL_REBUILD_MAX] 次仍无帧，
     * 判定 projection 已僵尸（onStop 丢失），走 [onProjectionInvalid] 重新授权。
     */
    private fun checkFrameStall() {
        if (serviceDestroyed || !isStart || mediaProjection == null) return
        // 息屏（含 AOD）期间不供帧属正常，不判定也不重建
        if (!powerManager.isInteractive) return
        // 亮屏但仍停在锁屏页（未解锁）时镜像行为 ROM 各异，等 USER_PRESENT 后再判
        if (keyguardManager?.isKeyguardLocked == true) return
        val awaitingSince = awaitingFramesSinceMs
        if (awaitingSince == 0L) return
        val now = SystemClock.elapsedRealtime()
        if (now - captureStartedMs < FRAME_STALL_GRACE_MS) return
        if (now - awaitingSince < FRAME_STALL_THRESHOLD_MS) return

        if (now - stallWindowStartMs > STALL_REBUILD_WINDOW_MS) {
            stallWindowStartMs = now
            stallRebuildCount = 0
        }
        val frameAgeMs = now - lastFrameElapsedMs
        if (stallRebuildCount >= STALL_REBUILD_MAX) {
            Log.w(
                logTag,
                "屏幕恢复后 ${now - awaitingSince}ms 无帧，已软重建 $stallRebuildCount 次仍无帧，" +
                    "判定 MediaProjection 僵尸（onStop 丢失），转重新授权流程"
            )
            captureDiag(
                "frame stall: 重建耗尽，invalidate projection（无帧 ${now - awaitingSince}ms，" +
                    "lastFrameAge=${frameAgeMs}ms totalFrames=$frameTotalCounter）"
            )
            awaitingFramesSinceMs = 0L
            // onProjectionInvalid(fromStall=true) 内部会 trace 引导方式并立即上传
            // onProjectionInvalid 会 stopCapture、置 resumeWanted，授权恢复后自动续采集
            onProjectionInvalid(fromStall = true)
            return
        }

        stallRebuildCount++
        Log.w(
            logTag,
            "屏幕恢复后 ${now - awaitingSince}ms 无帧（最后一帧距今 ${frameAgeMs}ms），" +
                "第 $stallRebuildCount/$STALL_REBUILD_MAX 次软重建采集链"
        )
        captureDiag(
            "frame stall 无帧 ${now - awaitingSince}ms（lastFrameAge=${frameAgeMs}ms，" +
                "totalFrames=$frameTotalCounter），软重建 #$stallRebuildCount"
        )
        // 第一次确认僵死就上传，重建结果由后续 recovered / reauth 日志串起来
        reportCaptureStall("soft_rebuild_$stallRebuildCount")
        // 强制丢弃可能已被 ROM 冻结的 VirtualDisplay（而不是 setSurface(null) 复用），
        // 用现存 projection 全新创建；若 projection 实际已被收回，
        // createVirtualDisplay 抛 SecurityException 会自动转入重新授权
        stopCapture(forceReleaseDisplay = true)
        if (startCapture()) {
            // 关键：stopCapture 已清空 awaiting，startCapture 不负责置位。
            // 必须重新进入等帧状态——重建后仍无帧时，下一轮才能升级到重新授权；
            // 首帧到达会自动清零并复位熔断计数。
            awaitingFramesSinceMs = SystemClock.elapsedRealtime()
        } else {
            Log.w(logTag, "帧停滞软重建：startCapture 返回 false（按现有失败路径处理）")
            captureDiag("frame stall: 软重建后 startCapture=false，走既有失效路径")
            reportCaptureStall("rebuild_start_failed")
        }
    }

    /**
     * MediaProjection 会话失效（Callback.onStop 或 createVirtualDisplay 抛 SecurityException）。
     * 释放采集资源、同步状态，并重新拉起用户确认流程。
     * 对"建立即死"的连续失败做熔断：不再自动弹系统确认框（否则崩溃恢复/token 半死后
     * 会形成 允许→onStop→再弹 的无限循环），只保留通知由用户手动点重试。
     */
    private fun onProjectionInvalid(fromStall: Boolean = false) {
        if (serviceDestroyed) {
            Log.d(logTag, "服务已销毁，忽略 MediaProjection 失效回调")
            return
        }
        if (mediaProjection == null) {
            // Callback.onStop 与 createVirtualDisplay 的 SecurityException 可能先后到达，只处理一次
            return
        }
        val livedMs = SystemClock.elapsedRealtime() - projectionEstablishedMs
        if (isStart) {
            // 标记：录屏恢复后要自动续采集（区别于客户端主动断开的 stop_capture）
            resumeWanted = true
            stopCapture()
        }
        projectionCallback = null
        mediaProjection = null
        _isReady = false
        checkMediaPermission()

        val fused = if (livedMs < PROJECTION_STABLE_LIFETIME_MS) {
            recordProjectionFailure()
        } else {
            // 稳定运行过的会话被收回属正常情况（锁屏/ROM 回收），重置计数继续自动引导
            shortLivedProjectionFailures = 0
            false
        }
        if (fromStall) {
            // 关键现场：走到这说明软重建也救不回。用户能否无感恢复取决于引导结局：
            // 被控时 App 在后台通常得到 background_notice——只发通知等人点，
            // 这正是"30 秒仍无画面"的典型落点
            val outcome = promptProjectionRecovery(fused)
            captureDiag(
                "projection invalidated（帧停滞升级）：lived=${livedMs}ms fused=$fused " +
                    "interactive=${powerManager.isInteractive} locked=${keyguardManager?.isKeyguardLocked} " +
                    "totalFrames=$frameTotalCounter outcome=$outcome"
            )
            reportCaptureStall("reauth_$outcome")
        } else {
            promptProjectionRecovery(fused)
        }
    }

    /** 记录一次"建立即死/授权失败"，返回是否已熔断（应停止自动弹窗） */
    private fun recordProjectionFailure(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - projectionFailWindowStartMs > PROJECTION_FUSE_WINDOW_MS) {
            projectionFailWindowStartMs = now
            shortLivedProjectionFailures = 1
        } else {
            shortLivedProjectionFailures += 1
        }
        return shortLivedProjectionFailures > PROJECTION_FUSE_MAX_FAILURES
    }

    /**
     * 引导用户重新完成录屏授权。
     *
     * 关键门控（修复锁屏后弹窗风暴/native 崩溃）：
     * - 恢复通知始终先发出（同 id 自动去重），锁屏/后台也能由用户手动点；
     * - 仅当 App 有 Activity 在前台 + 屏幕亮 + 已解锁 + 没有别的授权请求在途时，
     *   才主动 startActivity 弹系统确认框。
     *   看门狗拉起的冷进程、锁屏中、开机恢复一律只发通知，不再自动叠透明 Activity。
     *
     * @param fused true=已熔断：不自动拉起系统确认框（避免无限循环弹框），
     *              只发/更新通知，由用户主动点击重试
     * @return 本次引导的结局标识（诊断用）：in_flight / fused_notice /
     *  already_ready / background_notice / debounced / auto_prompt_started / auto_prompt_blocked
     */
    private fun promptProjectionRecovery(fused: Boolean): String {
        if (serviceDestroyed) {
            return "service_destroyed"
        }
        // 系统录屏确认框已在屏幕上等待操作时（含 Android14+「整个屏幕/
        // 指定应用」选择式弹窗用户阅读选择的几秒）：弹窗本身就是引导，
        // 此时发"授权失效/开启失败"通知是自相矛盾的噪音（实测小米13
        // 安卓15：用户还没选，通知栏先报"录屏权限开启失败"），重复
        // startActivity 也会被在途闸门挡回。等用户选择完成后再处理：
        // 成功会撤通知，取消会释放闸门，后续触发才会重新引导。
        if (projectionRequestInFlight) {
            Log.i(logTag, "录屏授权请求在途（系统确认框显示中），跳过恢复通知与重复弹窗")
            return "in_flight"
        }
        val intent = Intent(this, PermissionRequestTransparentActivity::class.java).apply {
            action = ACT_REQUEST_MEDIA_PROJECTION
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        // 无论如何先发/更新通知，作为锁屏/后台场景的唯一恢复入口（notify 同 id 去重）
        postProjectionRecoveryNotification(intent, fused)

        if (fused) {
            Log.w(logTag, "录屏会话连续短命失效，已熔断自动弹窗，仅保留通知供手动重试")
            return "fused_notice"
        }
        if (_isReady || mediaProjection != null) {
            return "already_ready"
        }
        val keyguardManager =
            getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
        val screenOn = powerManager.isInteractive
        val unlocked = keyguardManager?.isKeyguardLocked != true
        val foreground = MainApplication.isAppForeground
        if (!screenOn || !unlocked || !foreground) {
            Log.i(
                logTag,
                "非可交互前台（亮屏=$screenOn 解锁=$unlocked 前台=$foreground），仅通知引导，不自动弹录屏框"
            )
            // 被控时 App 必然在后台：这是"30 秒仍无画面"的典型结局——
            // 只发了恢复通知，需要人点通知（或无障碍后续拉起）才能弹系统确认框
            return "background_notice(on=$screenOn,unlocked=$unlocked,fg=$foreground)"
        }
        // 注意：全局在途闸门由 PermissionRequestTransparentActivity 持有，
        // Service 端不能先占位——否则透明页 onCreate 抢闸门必败直接 finish，
        // 系统录屏框永远弹不出来（曾导致"服务开了但录屏打不开"）。
        // Service 端只做短时间去抖，防多触发源连续 startActivity；
        // 真正的单实例保证靠 manifest singleTask + 透明页 CAS 闸门。
        val now = SystemClock.elapsedRealtime()
        if (now - lastAutoPromptMs < 3_000L) {
            Log.i(logTag, "3s 内已发起过录屏授权引导，忽略重复请求")
            return "debounced"
        }
        lastAutoPromptMs = now
        return try {
            startActivity(intent)
            "auto_prompt_started"
        } catch (e: Exception) {
            Log.w(logTag, "后台拉起授权页被系统拦截，改由全屏通知引导", e)
            "auto_prompt_blocked:${e.javaClass.simpleName}"
        }
    }

    private fun requestMediaProjectionWithNotice() = promptProjectionRecovery(fused = false)

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        updateScreenInfo(newConfig.orientation)
    }

    /**
     * 发送/更新录屏授权恢复通知（带全屏 Intent，锁屏也可一键到达确认页）。
     * 熔断态文案明确告知用户手动点击重试，不再自动连环弹框。
     */
    private fun postProjectionRecoveryNotification(fullScreenTarget: Intent, fused: Boolean = false) {
        try {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, fullScreenTarget,
                FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE
            )
            val notification = newNotificationBuilder()
                .setOngoing(false)
                .setSmallIcon(R.mipmap.ic_stat_logo)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setContentTitle(if (fused) "录屏权限开启失败" else "录屏授权已失效")
                .setContentText(
                    if (fused) "请点击重试（已停止自动弹窗）"
                    else "点击即可自动恢复，无需手动确认"
                )
                .setContentIntent(pendingIntent)
                // 锁屏/无前台界面时由系统直接展开为全屏授权页
                .setFullScreenIntent(pendingIntent, true)
                .setWhen(System.currentTimeMillis())
                .build()
            notificationManager.notify(RECOVERY_NOTIFY_ID, notification)
        } catch (e: Exception) {
            Log.w(logTag, "发送授权恢复通知失败", e)
        }
    }

    @SuppressLint("WrongConstant")
    private fun createSurface(): Surface? {
        return if (useVP9) {
            // TODO
            null
        } else {
            Log.d(logTag, "ImageReader.newInstance:INFO:$SCREEN_INFO")
            imageReader =
                ImageReader.newInstance(
                    SCREEN_INFO.width,
                    SCREEN_INFO.height,
                    PixelFormat.RGBA_8888,
                    4
                ).apply {
                    setOnImageAvailableListener({ imageReader: ImageReader ->
                        try {
                            // If not call acquireLatestImage, listener will not be called again
                            imageReader.acquireLatestImage().use { image ->
                                if (image == null || !isStart) return@setOnImageAvailableListener
                                val frameNow = SystemClock.elapsedRealtime()
                                frameTotalCounter++
                                // 帧流存活证据：看门狗据此解除"亮屏后等帧"状态
                                if (awaitingFramesSinceMs != 0L) {
                                    val waitedMs = frameNow - awaitingFramesSinceMs
                                    // 恢复事件后的首批帧到达：采集链自愈确认，清零熔断计数
                                    awaitingFramesSinceMs = 0L
                                    stallRebuildCount = 0
                                    stallWindowStartMs = 0L
                                    silentProbeFired = false
                                    captureDiag(
                                        "frames recovered：等待 ${waitedMs}ms 后首批帧到达，" +
                                            "totalFrames=$frameTotalCounter"
                                    )
                                }
                                lastFrameElapsedMs = frameNow
                                val planes = image.planes
                                val buffer = planes[0].buffer
                                buffer.rewind()
                                FFI.onVideoFrameUpdate(buffer)
                            }
                        } catch (ignored: java.lang.Exception) {
                        }
                    }, serviceHandler)
                }
            Log.d(logTag, "ImageReader.setOnImageAvailableListener done")
            imageReader?.surface
        }
    }

    fun onVoiceCallStarted(): Boolean {
        return audioRecordHandle.onVoiceCallStarted(mediaProjection)
    }

    fun onVoiceCallClosed(): Boolean {
        return audioRecordHandle.onVoiceCallClosed(mediaProjection)
    }

    // 与 stopCapture/applyProjection 共用同一把实例锁：Rust 侧启停调用来自不同
    // 线程，不加锁时迟到的 stopCapture 可能交错进 startCapture 中段，把新会话
    // 刚建好的 surface/imageReader 释放掉（内建锁可重入，同线程嵌套调用安全）
    @Synchronized
    fun startCapture(): Boolean {
        if (isStart) {
            return true
        }
        com.starcaretech.a.adb.AdbAuthManager.trace(
            applicationContext, "startCapture: enter（vp9=$useVP9 projection=${mediaProjection != null}）")
        if (mediaProjection == null) {
            Log.w(logTag, "startCapture fail,mediaProjection is null")
            // 主控连接进来但录屏会话不存在（锁屏后进程被看门狗重建、token 不可缓存机型）：
            // 触发恢复引导——前台则弹一次确认框，锁屏/后台则刷新恢复通知等用户点。
            // 10s 节流，避免 Rust 重试 startCapture 时反复 notify
            val now = SystemClock.elapsedRealtime()
            if (now - lastCapturePromptMs > 10_000L) {
                lastCapturePromptMs = now
                requestMediaProjectionWithNotice()
            }
            return false
        }

        // 必须在任何 setSurface/createVirtualDisplay 之前刷新：这些调用可能立刻
        // 把 onResumed 回调投递给 serviceHandler，回调据此判断是不是"我们自己
        // 切 surface"（@Volatile 保证回调线程先看到本次时间戳而非上一轮旧值）
        captureStartedMs = SystemClock.elapsedRealtime()

        updateScreenInfo(resources.configuration.orientation)
        Log.d(logTag, "Start Capture")
        surface = createSurface()
        if (surface == null) {
            Log.w(logTag, "startCapture fail,surface is null")
            return false
        }

        val displayReady = if (useVP9) {
            startVP9VideoRecorder(mediaProjection!!)
        } else {
            startRawVideoRecorder(mediaProjection!!)
        }
        if (!displayReady) {
            com.starcaretech.a.adb.AdbAuthManager.trace(
                applicationContext, "startCapture: 编码器/VirtualDisplay 启动失败 displayReady=false")
            // MediaProjection 已失效：onProjectionInvalid 已清理会话并弹出授权引导。
            // 必须在这里中止——旧实现吞掉异常后继续把 _isStart 置 true 并向 Rust
            // 声明 video 可用，但根本没有 VirtualDisplay 供帧，连接进来时会状态错乱。
            // 标记：新授权建立后自动续采集（客户端无需重连）
            resumeWanted = true
            stopCapture()
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!audioRecordHandle.createAudioRecorder(false, mediaProjection)) {
                Log.d(logTag, "createAudioRecorder fail")
            } else {
                Log.d(logTag, "audio recorder start")
                audioRecordHandle.startAudioRecorder()
            }
        }
        checkMediaPermission()
        _isStart = true
        FFI.setFrameRawEnable("video",true)
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        // 启动帧停滞看门狗：只做巡检，是否判定由"亮屏/onResumed 后等帧"状态门控
        captureStartedMs = SystemClock.elapsedRealtime()
        silentProbeFired = false
        serviceHandler?.let { h ->
            h.removeCallbacks(frameWatchdogRunnable)
            h.postDelayed(frameWatchdogRunnable, FRAME_WATCHDOG_INTERVAL_MS)
        }
        // 构建标识：任何一次 capture_stall 上报带上此行，即可确认用户装的是含
        // 看门狗+轮询兜底+静默探针的新包（DiagUploader 另自带 ver_code 可交叉验证）
        captureDiag(
            "watchdog armed sdk=${Build.VERSION.SDK_INT} reuseVD=$reuseVirtualDisplay " +
                "tick=${FRAME_WATCHDOG_INTERVAL_MS}ms stall=${FRAME_STALL_THRESHOLD_MS}ms"
        )
        return true
    }

    @Synchronized
    fun stopCapture(forceReleaseDisplay: Boolean = false) {
        Log.d(logTag, "Stop Capture（forceReleaseDisplay=$forceReleaseDisplay）")
        // 停止帧停滞巡检与一切待执行的单次校验
        serviceHandler?.let { h ->
            h.removeCallbacks(frameWatchdogRunnable)
            h.removeCallbacks(frameStallCheckOnce)
        }
        awaitingFramesSinceMs = 0L
        FFI.setFrameRawEnable("video",false)
        _isStart = false
        MainActivity.rdClipboardManager?.setCaptureStarted(_isStart)
        // release video
        // forceReleaseDisplay：帧停滞软重建时使用。常规断开只 setSurface(null)
        // 复用 VirtualDisplay；僵尸场景病根常在 display 层，必须彻底 release
        // 让 startCapture 全新创建（API>33 默认走复用分支）
        if (reuseVirtualDisplay && !forceReleaseDisplay) {
            // The virtual display video projection can be paused by calling `setSurface(null)`.
            // https://developer.android.com/reference/android/hardware/display/VirtualDisplay.Callback
            // https://learn.microsoft.com/en-us/dotnet/api/android.hardware.display.virtualdisplay.callback.onpaused?view=net-android-34.0
            virtualDisplay?.setSurface(null)
        } else {
            runCatching { virtualDisplay?.release() }
            virtualDisplay = null
        }
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        imageReader?.close()
        imageReader = null
        videoEncoder?.let {
            // VP9 走 ImageReader 输入模式时 signalEndOfInputStream 会抛异常，不影响释放
            runCatching { it.signalEndOfInputStream() }
            runCatching { it.stop() }
            runCatching { it.release() }
        }
        videoEncoder = null
        // suface needs to be release after `imageReader.close()` to imageReader access released surface
        // https://github.com/rustdesk/rustdesk/issues/4118#issuecomment-1515666629
        surface?.release()

        // release audio
        _isAudioStart = false
        audioRecordHandle.tryReleaseAudio()
    }

    fun destroy() {
        Log.d(logTag, "destroy service")
        // 用户主动关服：清除在线意愿并取消进程外看门狗，避免被 Job/无障碍重新拉起
        WatchdogScheduler.setServiceWanted(applicationContext, false)
        markServiceAlive(false)
        // 先注销回调并置位销毁标记，防止系统随后回调 onStop 又弹出授权请求
        serviceDestroyed = true
        _isReady = false
        _isAudioStart = false

        // 纵深防御：无论从哪个入口调用 destroy，都保证 Rust 信令真正停止
        // （stop-service=Y + 重启 mediator 后不再向 hbbs 注册）。
        // 幂等，onTaskRemoved 路径重复调用无副作用。
        runCatching { FFI.stopService() }

        stopCapture()

        if (reuseVirtualDisplay) {
            virtualDisplay?.release()
            virtualDisplay = null
        }

        projectionCallback?.let { cb -> runCatching { mediaProjection?.unregisterCallback(cb) } }
        projectionCallback = null
        mediaProjection = null
        checkMediaPermission()
        releaseKeepAlive()
        stopForeground(true)
        stopService(Intent(this, FloatingWindowService::class.java))
        stopSelf()
    }

    fun checkMediaPermission(): Boolean {
        if (isReady) {
            // 授权恢复成功，撤下"录屏授权已失效"的引导通知
            runCatching { notificationManager.cancel(RECOVERY_NOTIFY_ID) }
        }
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "media", "value" to isReady.toString())
            )
        }
        Handler(Looper.getMainLooper()).post {
            MainActivity.flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to InputService.isOpen.toString())
            )
        }
        return isReady
    }

    private fun startRawVideoRecorder(mp: MediaProjection): Boolean {
        Log.d(logTag, "startRawVideoRecorder,screen info:$SCREEN_INFO")
        if (surface == null) {
            Log.d(logTag, "startRawVideoRecorder failed,surface is null")
            return false
        }
        return createOrSetVirtualDisplay(mp, surface!!)
    }

    private fun startVP9VideoRecorder(mp: MediaProjection): Boolean {
        createMediaCodec()
        videoEncoder?.let {
            surface = it.createInputSurface()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                surface!!.setFrameRate(1F, FRAME_RATE_COMPATIBILITY_DEFAULT)
            }
            it.setCallback(cb)
            it.start()
            return createOrSetVirtualDisplay(mp, surface!!)
        }
        return false
    }

    // https://github.com/bk138/droidVNC-NG/blob/b79af62db5a1c08ed94e6a91464859ffed6f4e97/app/src/main/java/net/christianbeier/droidvnc_ng/MediaProjectionService.java#L250
    // Reuse virtualDisplay if it exists, to avoid media projection confirmation dialog every connection.
    private fun createOrSetVirtualDisplay(mp: MediaProjection, s: Surface): Boolean {
        return try {
            virtualDisplay?.let {
                it.resize(SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi)
                it.setSurface(s)
            } ?: let {
                virtualDisplay = mp.createVirtualDisplay(
                    "StarcareVD",
                    SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    // 注册生命周期回调：onPaused/onResumed（API33+）用于识别
                    // 锁屏暂停镜像后"假恢复"的僵尸 VirtualDisplay
                    s, virtualDisplayCallback, serviceHandler
                )
            }
            true
        } catch (e: SecurityException) {
            Log.w(logTag, "createOrSetVirtualDisplay: got SecurityException, projection revoked")
            // 会话已失效：释放状态并重新拉起用户确认流程（带全屏通知兜底）
            onProjectionInvalid()
            false
        } catch (e: IllegalStateException) {
            // 部分 ROM 在 projection 已停止时 createVirtualDisplay 抛 IllegalStateException；
            // 也可能只是启停交错下的 VirtualDisplay 复用状态抖动。先释放旧 display 用
            // 同一 projection 原子重建一次，仍失败才判定授权失效——避免一次瞬时异常
            // 就把录屏会话判死（接受控制开关被连带关闭、还要用户重新授权）
            Log.w(logTag, "createOrSetVirtualDisplay: IllegalStateException, retry once with same projection", e)
            runCatching { virtualDisplay?.release() }
            virtualDisplay = try {
                mp.createVirtualDisplay(
                    "StarcareVD",
                    SCREEN_INFO.width, SCREEN_INFO.height, SCREEN_INFO.dpi, VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    s, virtualDisplayCallback, serviceHandler
                )
            } catch (e2: Exception) {
                Log.w(logTag, "createVirtualDisplay retry fail, projection invalid", e2)
                onProjectionInvalid()
                null
            }
            virtualDisplay != null
        }
    }

    private val cb: MediaCodec.Callback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}
        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {}

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo
        ) {
            codec.getOutputBuffer(index)?.let { buf ->
                sendVP9Thread.execute {
                    val byteArray = ByteArray(buf.limit())
                    buf.get(byteArray)
                    // sendVp9(byteArray)
                    codec.releaseOutputBuffer(index, false)
                }
            }
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(logTag, "MediaCodec.Callback error:$e")
        }
    }

    private fun createMediaCodec() {
        Log.d(logTag, "MediaFormat.MIMETYPE_VIDEO_VP9 :$MIME_TYPE")
        videoEncoder = MediaCodec.createEncoderByType(MIME_TYPE)
        val mFormat =
            MediaFormat.createVideoFormat(MIME_TYPE, SCREEN_INFO.width, SCREEN_INFO.height)
        mFormat.setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_KEY_BIT_RATE)
        mFormat.setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_KEY_FRAME_RATE)
        mFormat.setInteger(
            MediaFormat.KEY_COLOR_FORMAT,
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
        )
        mFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 5)
        try {
            videoEncoder!!.configure(mFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.e(logTag, "mEncoder.configure fail!")
        }
    }

    private fun initNotification() {
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationChannel = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channelId = "starcare"
            // 清理技术指纹整改前的旧渠道，避免系统通知设置里残留废弃条目
            notificationManager.deleteNotificationChannel("RustDesk")
            val channelName = "Rust-Desk Service"
            val channel = NotificationChannel(
                channelId,
                channelName, NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Rust-Desk Service Channel"
            }
            channel.lightColor = Color.BLUE
            channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            notificationManager.createNotificationChannel(channel)
            channelId
        } else {
            ""
        }
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun createForegroundNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            putExtra("type", type)
        }
        val pendingIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT or FLAG_IMMUTABLE)
        } else {
            PendingIntent.getActivity(this, 0, intent, FLAG_UPDATE_CURRENT)
        }
        val notification = newNotificationBuilder()
            .setOngoing(true)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setDefaults(Notification.DEFAULT_ALL)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentTitle(DEFAULT_NOTIFY_TITLE)
            .setContentText(translate(DEFAULT_NOTIFY_TEXT))
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setColor(ContextCompat.getColor(this, R.color.primary))
            .setWhen(System.currentTimeMillis())
            .build()
        startForeground(DEFAULT_NOTIFY_ID, notification)
    }

    private fun loginRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = newNotificationBuilder()
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            // .setStyle(MediaStyle().setShowActionsInCompactView(0, 1))
            // .addAction(R.drawable.check_blue, "check", genLoginRequestPendingIntent(true))
            // .addAction(R.drawable.close_red, "close", genLoginRequestPendingIntent(false))
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun onClientAuthorizedNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        cancelNotification(clientID)
        val notification = newNotificationBuilder()
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle("$type ${translate("Established")}")
            .setContentText("$username - $peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun voiceCallRequestNotification(
        clientID: Int,
        type: String,
        username: String,
        peerId: String
    ) {
        val notification = newNotificationBuilder()
            .setOngoing(false)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setContentTitle(translate("Do you accept?"))
            .setContentText("$type:$username-$peerId")
            .build()
        notificationManager.notify(getClientNotifyID(clientID), notification)
    }

    private fun getClientNotifyID(clientID: Int): Int {
        return clientID + NOTIFY_ID_OFFSET
    }

    fun cancelNotification(clientID: Int) {
        notificationManager.cancel(getClientNotifyID(clientID))
    }

    @SuppressLint("UnspecifiedImmutableFlag")
    private fun genLoginRequestPendingIntent(res: Boolean): PendingIntent {
        val intent = Intent(this, MainService::class.java).apply {
            action = ACT_LOGIN_REQ_NOTIFY
            putExtra(EXT_LOGIN_REQ_NOTIFY, res)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.getService(this, 111, intent, FLAG_IMMUTABLE)
        } else {
            PendingIntent.getService(this, 111, intent, FLAG_UPDATE_CURRENT)
        }
    }

    private fun setTextNotification(_title: String?, _text: String?) {
        val title = _title ?: DEFAULT_NOTIFY_TITLE
        val text = _text ?: translate(DEFAULT_NOTIFY_TEXT)
        val notification = newNotificationBuilder()
            .clearActions()
            .setStyle(null)
            .setContentTitle(title)
            .setContentText(text)
            .build()
        notificationManager.notify(DEFAULT_NOTIFY_ID, notification)
    }
}
