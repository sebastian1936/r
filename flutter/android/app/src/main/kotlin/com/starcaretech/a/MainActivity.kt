package com.starcaretech.a

/**
 * Handle events from flutter
 * Request MediaProjection permission
 *
 * Inspired by [droidVNC-NG] https://github.com/bk138/droidVNC-NG
 */

import ffi.FFI

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
import android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.DisplayMetrics
import androidx.annotation.RequiresApi
import org.json.JSONArray
import org.json.JSONObject
import com.hjq.permissions.XXPermissions
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import kotlin.concurrent.thread
import androidx.core.content.ContextCompat
import com.starcaretech.a.adb.AdbAuthManager
import com.starcaretech.a.adb.AdbPairingService


class MainActivity : FlutterActivity() {
    companion object {
        var flutterMethodChannel: MethodChannel? = null
        private var _rdClipboardManager: RdClipboardManager? = null
        val rdClipboardManager: RdClipboardManager?
            get() = _rdClipboardManager;

        // 主控/被控双包拆分：controller 包不含被控组件（Manifest 已移除），
        // 所有被控服务调用路径必须拦截，避免运行时崩溃
        val isController: Boolean
            get() = BuildConfig.FLAVOR == "controller"
    }

    private val channelTag = "mChannel"
    private val logTag = "mMainActivity"
    private var mainService: MainService? = null

    private var isAudioStart = false
    private val audioRecordHandle = AudioRecordHandle(this, { false }, { isAudioStart })

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        if (!isController && MainService.isReady) {
            Intent(activity, MainService::class.java).also {
                bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
            }
        }
        flutterMethodChannel = MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            channelTag
        )
        initFlutterChannel(flutterMethodChannel!!)
        thread {
            try {
                setCodecInfo()
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to setCodecInfo: ${e.message}", e)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val inputPer = InputService.isOpen
        activity.runOnUiThread {
            flutterMethodChannel?.invokeMethod(
                "on_state_changed",
                mapOf("name" to "input", "value" to inputPer.toString())
            )
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_INVOKE_PERMISSION_ACTIVITY_MEDIA_PROJECTION && resultCode == RES_FAILED) {
            flutterMethodChannel?.invokeMethod("on_media_projection_canceled", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (_rdClipboardManager == null) {
            _rdClipboardManager = RdClipboardManager(getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            FFI.setClipboardManager(_rdClipboardManager!!)
        }
        // 上次发生崩溃（Java/native 疑似）时弹窗，一键分享完整日志定位问题
        window.decorView.postDelayed({ showLastCrashDialogIfAny() }, 1200)
    }

    private fun showLastCrashDialogIfAny() {
        val crashText = CrashLogger.consumeIfNew(this) ?: return
        try {
            val preview = crashText.take(1500)
            android.app.AlertDialog.Builder(this)
                .setTitle("程序上次异常退出")
                .setMessage("已自动记录崩溃日志，点「分享日志」可发给开发者定位。\n\n$preview")
                .setPositiveButton("分享日志") { _, _ ->
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "RustDesk 崩溃日志")
                        // Binder 事务有 1MB 上限，截断到安全范围
                        putExtra(Intent.EXTRA_TEXT, crashText.take(90_000))
                    }
                    runCatching {
                        startActivity(Intent.createChooser(send, "分享崩溃日志"))
                    }
                }
                .setNegativeButton("关闭", null)
                .setCancelable(true)
                .show()
        } catch (e: Exception) {
            Log.e(logTag, "show crash dialog fail", e)
        }
    }

    override fun onDestroy() {
        Log.e(logTag, "onDestroy")
        mainService?.let {
            unbindService(serviceConnection)
        }
        super.onDestroy()
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            Log.d(logTag, "onServiceConnected")
            val binder = service as MainService.LocalBinder
            mainService = binder.getService()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(logTag, "onServiceDisconnected")
            mainService = null
        }
    }

    private fun initFlutterChannel(flutterMethodChannel: MethodChannel) {
        flutterMethodChannel.setMethodCallHandler { call, result ->
            // make sure result will be invoked, otherwise flutter will await forever
            when (call.method) {
                "init_service" -> {
                    // controller 包无被控服务，直接拦截
                    if (isController) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    Intent(activity, MainService::class.java).also {
                        bindService(it, serviceConnection, Context.BIND_AUTO_CREATE)
                    }
                    if (MainService.isReady) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    // 优先静默恢复缓存的录屏授权，失败由 MainService 弹系统确认框
                    Intent(activity, MainService::class.java).apply {
                        action = ACT_TRY_RESTORE_MEDIA_PROJECTION
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            activity.startForegroundService(this)
                        } else {
                            activity.startService(this)
                        }
                    }
                    result.success(true)
                }
                "start_capture" -> {
                    mainService?.let {
                        result.success(it.startCapture())
                    } ?: let {
                        result.success(false)
                    }
                }
                "stop_service" -> {
                    Log.d(logTag, "Stop service")
                    mainService?.let {
                        it.destroy()
                        result.success(true)
                    } ?: let {
                        result.success(false)
                    }
                }
                "check_permission" -> {
                    if (call.arguments is String) {
                        result.success(XXPermissions.isGranted(context, call.arguments as String))
                    } else {
                        result.success(false)
                    }
                }
                "request_permission" -> {
                    if (call.arguments is String) {
                        requestPermission(context, call.arguments as String)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                START_ACTION -> {
                    if (call.arguments is String) {
                        startAction(context, call.arguments as String)
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                "check_video_permission" -> {
                    mainService?.let {
                        result.success(it.checkMediaPermission())
                    } ?: let {
                        result.success(false)
                    }
                }
                "check_service" -> {
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "media", "value" to MainService.isReady.toString())
                    )
                    result.success(true)
                }
                "stop_input" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        InputService.ctx?.disableSelf()
                    }
                    InputService.ctx = null
                    Companion.flutterMethodChannel?.invokeMethod(
                        "on_state_changed",
                        mapOf("name" to "input", "value" to InputService.isOpen.toString())
                    )
                    result.success(true)
                }
                "cancel_notification" -> {
                    if (call.arguments is Int) {
                        val id = call.arguments as Int
                        mainService?.cancelNotification(id)
                    } else {
                        result.success(true)
                    }
                }
                "enable_soft_keyboard" -> {
                    // https://blog.csdn.net/hanye2020/article/details/105553780
                    if (call.arguments as Boolean) {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    } else {
                        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
                    }
                    result.success(true)

                }
                "try_sync_clipboard" -> {
                    rdClipboardManager?.syncClipboard(true)
                    result.success(true)
                }
                GET_START_ON_BOOT_OPT -> {
                    val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                    result.success(prefs.getBoolean(KEY_START_ON_BOOT_OPT, false))
                }
                SET_START_ON_BOOT_OPT -> {
                    if (call.arguments is Boolean) {
                        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                        val edit = prefs.edit()
                        edit.putBoolean(KEY_START_ON_BOOT_OPT, call.arguments as Boolean)
                        edit.apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                SYNC_APP_DIR_CONFIG_PATH -> {
                    if (call.arguments is String) {
                        val prefs = getSharedPreferences(KEY_SHARED_PREFERENCES, MODE_PRIVATE)
                        val edit = prefs.edit()
                        edit.putString(KEY_APP_DIR_CONFIG_PATH, call.arguments as String)
                        edit.apply()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }
                GET_VALUE -> {
                    if (call.arguments is String) {
                        if (call.arguments == KEY_IS_SUPPORT_VOICE_CALL) {
                            result.success(isSupportVoiceCall())
                        } else {
                            result.error("-1", "No such key", null)
                        }
                    } else {
                        result.success(null)
                    }
                }
                "on_voice_call_started" -> {
                    onVoiceCallStarted()
                }
                "on_voice_call_closed" -> {
                    onVoiceCallClosed()
                }
                "adb_auth_status" -> {
                    // ADB 一键授权状态：系统是否支持 + 是否已生效（pm 授权/无障碍名单/
                    // 服务运行中，三重判据）+ 是否有待引导的录屏授权
                    val granted = AdbAuthManager.isEnabled(context)
                    // 明细只进日志，不进 UI；状态误报时用 logcat（tag AdbAuth）定位
                    Log.i("AdbAuth", "auth status granted=$granted snap=${AdbAuthManager.statusSnapshot(context)}")
                    result.success(
                        mapOf(
                            "supported" to AdbAuthManager.isSupported(),
                            "granted" to granted,
                            "capture_pending" to AdbAuthManager.peekCapturePending(context),
                            "reason" to (AdbAuthManager.unsupportedReason() ?: "")
                        )
                    )
                }
                "adb_ensure_capture" -> {
                    // 配对完成后的录屏授权引导（必须 App 在前台调用）：
                    // 录屏（MediaProjection）是系统级 consent，任何 ADB/root 手段都无法
                    // 静默授予，只能拉起系统确认框让用户点一次；targetSdk33 下同一开机周期
                    // token 可复用（MainService 会缓存）。已就绪或缓存有效则不弹窗。
                    if (isController) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    // 消费一次性标记：无论就绪与否，这次回前台只引导一次
                    AdbAuthManager.consumeCapturePending(context)
                    try {
                        if (!MainService.isReady) {
                            val svc = Intent(context, MainService::class.java)
                                .setAction(ACT_TRY_RESTORE_MEDIA_PROJECTION)
                            ContextCompat.startForegroundService(context, svc)
                        }
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e("AdbAuth", "拉起录屏授权失败", e)
                        result.success(false)
                    }
                }
                "adb_pair_and_grant" -> {
                    // args: Map{addr: String(IP:端口), code: String(6位码)}，分屏模式下由 Dialog 传入
                    val args = call.arguments as? Map<*, *>
                    val code = args?.get("code") as? String
                    val addr = (args?.get("addr") as? String)?.trim().orEmpty()
                    if (code == null || code.length != 6) {
                        result.error("-1", "配对码必须为 6 位数字", null)
                        return@setMethodCallHandler
                    }
                    // addr 可空：分屏模式下同样由 mDNS 自动发现配对端口（与通知模式/Shizuku 一致）；
                    // 只有用户显式填写 IP:端口 时才解析
                    val port = if (addr.isBlank()) -1 else
                        try { addr.split(':').last().toInt() } catch (_: Exception) { 0 }
                    if (addr.isNotBlank() && port <= 0) {
                        result.error("-1", "IP:端口格式不正确", null)
                        return@setMethodCallHandler
                    }
                    if (!AdbAuthManager.isSupported()) {
                        result.error("-1", AdbAuthManager.unsupportedReason() ?: "当前系统不支持", null)
                        return@setMethodCallHandler
                    }
                    thread {
                        try {
                            val r = if (port > 0) {
                                AdbAuthManager.pairAndGrant(context, code, "127.0.0.1", port)
                            } else {
                                AdbAuthManager.pairAndGrantAuto(context, code)
                            }
                            activity.runOnUiThread {
                                result.success(mapOf("guid" to r.guid, "shell_direct" to r.shellDirect))
                            }
                        } catch (e: Exception) {
                            Log.e("AdbAuth", "分屏配对授权失败", e)
                            activity.runOnUiThread {
                                // message 可能为空（如底层 NPE/InterruptedException），
                                // 必须带上异常类型，否则用户只看到无信息量的"请重试"
                                val msg = e.message?.takeIf { it.isNotBlank() }
                                    ?: "授权失败（${e.javaClass.simpleName}），请重试"
                                result.error("-1", msg, null)
                            }
                        }
                    }
                }
                "adb_start_pairing" -> {
                    // 通知栏 RemoteInput 配对（仿 Shizuku，无需分屏）：
                    // 启动前台服务，mDNS 自动发现配对端口，用户在通知里输入 6 位码即可。
                    if (isController) {
                        result.error("-1", "当前安装包不支持该功能", null)
                        return@setMethodCallHandler
                    }
                    if (!AdbAuthManager.isSupported()) {
                        result.error("-1", AdbAuthManager.unsupportedReason() ?: "当前系统不支持", null)
                        return@setMethodCallHandler
                    }
                    val nm = context.getSystemService(NotificationManager::class.java)
                    AdbPairingService.ensureChannel(context)

                    // 权限/开关全部通过后的真正启动逻辑
                    fun proceed() {
                        // 国产 ROM 常见：运行时权限已授予，但通知总开关或本渠道默认关闭，
                        // 此时 startForeground 不报错，通知却完全不显示——必须提前拦截
                        val globalEnabled = nm.areNotificationsEnabled()
                        val channel = nm.getNotificationChannel(AdbPairingService.CHANNEL_ID)
                        val channelEnabled =
                            channel == null || channel.importance != NotificationManager.IMPORTANCE_NONE
                        if (!globalEnabled || !channelEnabled) {
                            activity.runOnUiThread {
                                result.error(
                                    "NOTIFICATION_DISABLED",
                                    "系统通知已被关闭（部分国产 ROM 默认关闭新应用通知），配对通知无法显示。请点下方「打开通知设置」开启后重试",
                                    null
                                )
                            }
                            return
                        }
                        try {
                            ContextCompat.startForegroundService(
                                context, AdbPairingService.startIntent(context)
                            )
                            activity.runOnUiThread { result.success(true) }
                        } catch (e: Exception) {
                            Log.e("MainActivity", "start pairing service failed", e)
                            activity.runOnUiThread {
                                result.error(
                                    "START_FAILED",
                                    "配对服务启动失败：${e.message ?: e.javaClass.simpleName}",
                                    null
                                )
                            }
                        }
                    }

                    // POST_NOTIFICATIONS 是 Android 13(API33) 才引入的运行时权限；
                    // Android 11/12 上该权限名不存在，绝不能去申请（系统会直接判拒绝）
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                        !XXPermissions.isGranted(context, android.Manifest.permission.POST_NOTIFICATIONS)
                    ) {
                        // 必须在 Activity 前台时申请；进入系统配对页之后不能再打断
                        XXPermissions.with(activity)
                            .permission(android.Manifest.permission.POST_NOTIFICATIONS)
                            .request { _, all ->
                                if (all) {
                                    proceed()
                                } else {
                                    activity.runOnUiThread {
                                        result.error(
                                            "NEED_PERMISSION",
                                            "需要通知权限：配对码要在下拉通知栏里输入（这样系统配对页不会切后台失效）。请授予通知权限后重试",
                                            null
                                        )
                                    }
                                }
                            }
                    } else {
                        proceed()
                    }
                }
                "adb_open_notification_settings" -> {
                    // 直达本应用系统通知设置（国产 ROM 自救入口），失败再退到应用详情页
                    val opened = try {
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        true
                    } catch (e: Exception) {
                        runCatching {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(android.net.Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            )
                        }.isSuccess
                    }
                    result.success(opened)
                }
                "adb_repair" -> {
                    // 手动触发一次无障碍自愈
                    thread {
                        val ok = AdbAuthManager.repairAccessibility(context)
                        activity.runOnUiThread { result.success(ok) }
                    }
                }
                else -> {
                    result.error("-1", "No such method", null)
                }
            }
        }
    }

    private fun setCodecInfo() {
        val codecList = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val codecs = codecList.codecInfos
        val codecArray = JSONArray()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val wh = getScreenSize(windowManager)
        var w = wh.first
        var h = wh.second
        val align = 64
        w = (w + align - 1) / align * align
        h = (h + align - 1) / align * align
        codecs.forEach { codec ->
            val codecObject = JSONObject()
            codecObject.put("name", codec.name)
            codecObject.put("is_encoder", codec.isEncoder)
            var hw: Boolean? = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                hw = codec.isHardwareAccelerated
            } else {
                // https://chromium.googlesource.com/external/webrtc/+/HEAD/sdk/android/src/java/org/webrtc/MediaCodecUtils.java#29
                // https://chromium.googlesource.com/external/webrtc/+/master/sdk/android/api/org/webrtc/HardwareVideoEncoderFactory.java#229
                if (listOf("OMX.google.", "OMX.SEC.", "c2.android").any { codec.name.startsWith(it, true) }) {
                    hw = false
                } else if (listOf("c2.qti", "OMX.qcom.video", "OMX.Exynos", "OMX.hisi", "OMX.MTK", "OMX.Intel", "OMX.Nvidia").any { codec.name.startsWith(it, true) }) {
                    hw = true
                }
            }
            if (hw != true) {
                return@forEach
            }
            codecObject.put("hw", hw)
            var mime_type = ""
            codec.supportedTypes.forEach { type ->
                if (listOf("video/avc", "video/hevc").contains(type)) { // "video/x-vnd.on2.vp8", "video/x-vnd.on2.vp9", "video/av01"
                    mime_type = type;
                }
            }
            if (mime_type.isNotEmpty()) {
                codecObject.put("mime_type", mime_type)
                val caps = codec.getCapabilitiesForType(mime_type)
                if (codec.isEncoder) {
                    // Encoder's max_height and max_width are interchangeable
                    if (!caps.videoCapabilities.isSizeSupported(w,h) && !caps.videoCapabilities.isSizeSupported(h,w)) {
                        return@forEach
                    }
                }
                codecObject.put("min_width", caps.videoCapabilities.supportedWidths.lower)
                codecObject.put("max_width", caps.videoCapabilities.supportedWidths.upper)
                codecObject.put("min_height", caps.videoCapabilities.supportedHeights.lower)
                codecObject.put("max_height", caps.videoCapabilities.supportedHeights.upper)
                val surface = caps.colorFormats.contains(COLOR_FormatSurface);
                codecObject.put("surface", surface)
                val nv12 = caps.colorFormats.contains(COLOR_FormatYUV420SemiPlanar)
                codecObject.put("nv12", nv12)
                if (!(nv12 || surface)) {
                    return@forEach
                }
                codecObject.put("min_bitrate", caps.videoCapabilities.bitrateRange.lower / 1000)
                codecObject.put("max_bitrate", caps.videoCapabilities.bitrateRange.upper / 1000)
                if (!codec.isEncoder) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        codecObject.put("low_latency", caps.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency))
                    }
                }
                if (!codec.isEncoder) {
                    return@forEach
                }
                codecArray.put(codecObject)
            }
        }
        val result = JSONObject()
        result.put("version", Build.VERSION.SDK_INT)
        result.put("w", w)
        result.put("h", h)
        result.put("codecs", codecArray)
        FFI.setCodecInfo(result.toString())
    }

    private fun onVoiceCallStarted() {
        var ok = false
        mainService?.let {
            ok = it.onVoiceCallStarted()
        } ?: let {
            isAudioStart = true
            ok = audioRecordHandle.onVoiceCallStarted(null)
        }
        if (!ok) {
            // Rarely happens, So we just add log and msgbox here.
            Log.e(logTag, "onVoiceCallStarted fail")
            flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call",
                "text" to "Failed to start voice call."))
        } else {
            Log.d(logTag, "onVoiceCallStarted success")
        }
    }

    private fun onVoiceCallClosed() {
        var ok = false
        mainService?.let {
            ok = it.onVoiceCallClosed()
        } ?: let {
            isAudioStart = false
            ok = audioRecordHandle.onVoiceCallClosed(null)
        }
        if (!ok) {
            // Rarely happens, So we just add log and msgbox here.
            Log.e(logTag, "onVoiceCallClosed fail")
            flutterMethodChannel?.invokeMethod("msgbox", mapOf(
                "type" to "custom-nook-nocancel-hasclose-error",
                "title" to "Voice call",
                "text" to "Failed to stop voice call."))
        } else {
            Log.d(logTag, "onVoiceCallClosed success")
        }
    }

    override fun onStop() {
        super.onStop()
        // controller 包无 FloatingWindowService（Manifest 已移除），不启动
        if (isController) {
            return
        }
        val disableFloatingWindow = FFI.getLocalOption("disable-floating-window") == "Y"
        if (!disableFloatingWindow && MainService.isReady) {
            startService(Intent(this, FloatingWindowService::class.java))
        }
    }

    override fun onStart() {
        super.onStart()
        stopService(Intent(this, FloatingWindowService::class.java))
    }
}
