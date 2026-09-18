package com.starcaretech.a.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.starcaretech.a.MainActivity
import com.starcaretech.a.R
import kotlin.concurrent.thread

/**
 * 无线调试配对前台服务（交互方式源自 Shizuku AdbPairingService）。
 *
 * 为什么用通知 + RemoteInput，而不是分屏对话框：
 * 系统"使用配对码配对设备"页面一旦进入后台（onStop），配对码和配对端口立即失效。
 * 下拉通知栏时 SystemUI 是覆盖窗而不是 Activity，系统配对页仍处于 RESUMED 状态，
 * 配对码不会失效；RemoteInput 的输入框也由 SystemUI 提供，全程不把任何 Activity
 * 切到前台。用户只需：打开配对页 → 下拉通知栏 → 输入 6 位码 → 点发送。
 *
 * 端口不需要用户输入：服务在后台持续 mDNS 扫描 _adb-tls-pairing._tcp，
 * 发现有效端口后把端口写进 RemoteInput 的 PendingIntent，回传时直接使用。
 */
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "AdbPairingService"
        private const val CHANNEL_ID = "adb_pairing"
        private const val NOTIFICATION_ID = 3

        private const val ACTION_START = "com.starcaretech.a.adb.START"
        private const val ACTION_REPLY = "com.starcaretech.a.adb.REPLY"
        private const val ACTION_STOP = "com.starcaretech.a.adb.STOP"
        private const val ACTION_RETRY = "com.starcaretech.a.adb.RETRY"

        private const val EXTRA_PORT = "port"
        private const val KEY_CODE = "pairing_code"

        fun startIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_START)
    }

    private var discovery: AdbDiscovery.Continuous? = null

    /** 当前已确认有效的配对端口（RemoteInput 回传前可能服务已重建，也放进 Intent 双保险） */
    @Volatile
    private var currentPort = -1

    /** 正在配对/授权，忽略重复的 discovery 回调 */
    @Volatile
    private var busy = false

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "无线调试配对",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "无线调试一键配对授权时显示"
                    setSound(null, null)
                    setShowBadge(false)
                }
            )
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RETRY -> beginSearch()
            ACTION_REPLY -> handleReply(intent)
            ACTION_STOP -> {
                finishAndStop(removeNotification = true)
                return START_NOT_STICKY
            }
            else -> return START_NOT_STICKY
        }
        return START_REDELIVER_INTENT
    }

    // ---------------------------------------------------------------- 搜索阶段

    private fun beginSearch() {
        busy = false
        currentPort = -1
        startForeground(NOTIFICATION_ID, searchingNotification())
        if (discovery == null) {
            discovery = AdbDiscovery.Continuous(
                this,
                AdbDiscovery.TYPE_PAIRING,
                onValidPort = { port -> onPairingPortFound(port) },
                onLost = { onPairingServiceLost() }
            )
        }
        discovery?.start()
    }

    private fun onPairingPortFound(port: Int) {
        if (busy) return
        if (port == currentPort) return
        currentPort = port
        Log.i(TAG, "发现配对服务端口: $port")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, inputNotification(port))
    }

    private fun onPairingServiceLost() {
        if (busy) return
        currentPort = -1
        Log.i(TAG, "配对服务消失，恢复搜索状态通知")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, searchingNotification())
    }

    // ---------------------------------------------------------------- 输入回传

    private fun handleReply(intent: Intent) {
        // RemoteInput 的 PendingIntent 走 getForegroundService：若进程被杀后由回传 Intent
        // 单独重建服务，系统要求 5 秒内 startForeground，否则 Android 12+ 直接崩溃。
        startForeground(NOTIFICATION_ID, workingNotification())
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_CODE)?.toString()?.trim().orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, currentPort).takeIf { it > 0 } ?: currentPort

        if (!code.matches(Regex("\\d{6}"))) {
            postResult(
                success = false,
                shortText = "配对码必须是 6 位数字，请重试",
                detail = "收到的输入：\"$code\""
            )
            return
        }
        if (port <= 0) {
            postResult(
                success = false,
                shortText = "未获取到配对端口，请重新打开配对页后点重试",
                detail = null
            )
            return
        }

        busy = true
        discovery?.stop()
        startForeground(NOTIFICATION_ID, workingNotification())

        thread(name = "adb-pair-grant") {
            try {
                // 完整闭环：配对 → 发现 connect 端口 → pm grant → 验证
                val guid = AdbAuthManager.pairAndGrant(
                    applicationContext, code, "127.0.0.1", port
                )
                postResult(success = true, shortText = "授权成功，权限自动恢复已开启", detail = "设备 GUID：$guid")
                finishAndStop(removeNotification = false)
            } catch (e: Exception) {
                Log.w(TAG, "配对授权失败", e)
                postResult(
                    success = false,
                    shortText = "配对或授权失败，点通知上的「重试」再来一次",
                    detail = e.message ?: e.javaClass.name
                )
                // 失败后保持服务存活，用户可在通知上直接重试（重新扫配对端口）
                busy = false
            }
        }
    }

    // ---------------------------------------------------------------- 通知

    private fun baseBuilder(): Notification.Builder =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_stat_logo)
            .setColor(0xFF2196F3.toInt())
            .setOnlyAlertOnce(true)

    private fun searchingNotification(): Notification =
        baseBuilder()
            .setContentTitle("正在搜索无线调试配对服务…")
            .setContentText("请打开系统「无线调试 → 使用配对码配对设备」页面")
            .addAction(stopAction())
            .build()

    private fun workingNotification(): Notification =
        baseBuilder()
            .setContentTitle("正在配对并授予权限…")
            .setContentText("请勿关闭系统无线调试页面")
            .build()

    private fun inputNotification(port: Int): Notification {
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("输入 6 位配对码")
            .build()

        val replyIntent = Intent(this, AdbPairingService::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_PORT, port)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val replyPi = PendingIntent.getForegroundService(this, NOTIFICATION_ID, replyIntent, flags)

        val action = Notification.Action.Builder(null, "输入配对码", replyPi)
            .addRemoteInput(remoteInput)
            .build()

        return baseBuilder()
            .setContentTitle("已发现配对服务，点这里输入配对码")
            .setContentText("配对页保持显示，直接下拉通知栏输入即可，无需分屏")
            .addAction(action)
            .addAction(stopAction())
            .build()
    }

    private fun stopAction(): Notification.Action {
        val pi = PendingIntent.getService(
            this, 10,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, "取消", pi).build()
    }

    private fun retryAction(): Notification.Action {
        val pi = PendingIntent.getService(
            this, 11,
            Intent(this, AdbPairingService::class.java).setAction(ACTION_RETRY),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Action.Builder(null, "重试", pi).build()
    }

    /** 结果通知以普通通知形式保留（脱离前台服务生命周期），点击可回到 App */
    private fun postResult(success: Boolean, shortText: String, detail: String?) {
        val openPi = PendingIntent.getActivity(
            this, 12,
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val builder = baseBuilder()
            .setContentTitle(if (success) "无线调试授权成功" else "无线调试授权失败")
            .setContentText(shortText)
            .setContentIntent(openPi)
            .setAutoCancel(true)
        if (detail != null) {
            builder.setStyle(Notification.BigTextStyle().bigText("$shortText\n\n$detail"))
        }
        if (!success) {
            builder.addAction(retryAction())
            builder.addAction(stopAction())
        }
        val notification = builder.build()
        lastResultNotification = notification
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun finishAndStop(removeNotification: Boolean) {
        discovery?.stop()
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        // 成功路径：结果通知已在 stopForeground 之前 post，这里需要重新补发一次
        // （STOP_FOREGROUND_REMOVE 会连普通通知一并移除；DETACH 常量需 API 24，minSdk 22 不能用）
        if (!removeNotification) {
            // 重新发送最近一次结果：由调用方在 stopSelf 前通过 postResult 已构造
            lastResultNotification?.let {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, it)
            }
        }
        stopSelf()
    }

    @Volatile
    private var lastResultNotification: Notification? = null

    override fun onDestroy() {
        discovery?.stop()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
