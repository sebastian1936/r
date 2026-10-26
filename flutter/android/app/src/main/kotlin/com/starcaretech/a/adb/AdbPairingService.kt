package com.starcaretech.a.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
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
 * 端口默认不需要用户输入：服务在后台持续 mDNS 扫描 _adb-tls-pairing._tcp，
 * 发现有效端口后把端口写进 RemoteInput 的 PendingIntent，回传时直接使用。
 *
 * 兜底：部分 ROM（实测 ColorOS/realme UI）上 NsdManager 可能始终不把配对
 * 服务送上来，通知一直停在"正在搜索"。此时通知上提供「手动输入」：用户照抄
 * 系统配对页显示的「IP 地址和端口」（形如 192.168.1.5:37211）直接回传，
 * 完全跳过 mDNS；配对连接对该地址发起（TLS 不校验主机名，局域网直连同机 adbd）。
 */
class AdbPairingService : Service() {

    companion object {
        private const val TAG = "AdbPairingService"
        const val CHANNEL_ID = "adb_pairing"
        private const val NOTIFICATION_ID = 3

        private const val ACTION_START = "com.starcaretech.a.adb.START"
        private const val ACTION_REPLY = "com.starcaretech.a.adb.REPLY"
        private const val ACTION_STOP = "com.starcaretech.a.adb.STOP"
        private const val ACTION_RETRY = "com.starcaretech.a.adb.RETRY"
        // mDNS 始终扫不到配对端口时的兜底：用户照抄系统配对页显示的
        // 「IP:端口」，通过通知栏 RemoteInput 直接回传，跳过服务发现
        private const val ACTION_MANUAL_ADDR = "com.starcaretech.a.adb.MANUAL_ADDR"
        // 配对成功但 mDNS 发现不到 connect 服务时的第二阶段兜底：
        // 用户照抄无线调试【主页面】显示的「IP:端口」回传
        private const val ACTION_MANUAL_CONNECT = "com.starcaretech.a.adb.MANUAL_CONNECT"
        // 用户放弃配对，回退到传统手动授权（手动开无障碍+录屏）。
        // 由失败通知动作以 Activity PendingIntent extra 传给 MainActivity
        const val EXTRA_MANUAL_FALLBACK = "manual_fallback"
        private const val ACTION_MANUAL_ACTIVITY = "com.starcaretech.a.adb.MANUAL_FALLBACK"

        private const val EXTRA_PORT = "port"
        private const val EXTRA_HOST = "host"
        private const val KEY_CODE = "pairing_code"
        private const val KEY_ADDR = "manual_addr"
        private const val KEY_CONNECT_ADDR = "manual_connect_addr"

        /** 第二阶段手动输入连接地址的最长等待（shortService 前台类型总时长约 3 分钟） */
        private const val MANUAL_CONNECT_WAIT_MS = 110_000L

        fun startIntent(context: Context): Intent =
            Intent(context, AdbPairingService::class.java).setAction(ACTION_START)

        /** 提前/重复创建通知渠道（Service.onCreate 与 Activity 启动前自检共用） */
        fun ensureChannel(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
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
    }

    private var discovery: AdbDiscovery.Continuous? = null

    /** 当前已确认有效的配对端口（RemoteInput 回传前可能服务已重建，也放进 Intent 双保险） */
    @Volatile
    private var currentPort = -1

    /** 当前配对地址：自动发现固定 127.0.0.1；手动兜底时为用户填写的局域网 IP */
    @Volatile
    private var currentHost = "127.0.0.1"

    /** 用户已改用手动输入地址端口：停止 mDNS 发现，忽略此后迟到的发现回调 */
    @Volatile
    private var manualMode = false

    /** 正在配对/授权，忽略重复的 discovery 回调 */
    @Volatile
    private var busy = false

    /** 第二阶段（connect 端点）手动输入的同步原语：授权工作线程阻塞等待通知栏回传 */
    @Volatile
    private var connectLatch = java.util.concurrent.CountDownLatch(1)

    @Volatile
    private var connectEndpoint: Pair<String, Int>? = null

    /** 正在等待用户回传 connect 地址：格式错误时刷新同一通知让用户重输，不解除等待 */
    @Volatile
    private var connectRequestActive = false

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START, ACTION_RETRY -> beginSearch()
            ACTION_REPLY -> handleReply(intent)
            ACTION_MANUAL_ADDR -> handleManualAddr(intent)
            ACTION_MANUAL_CONNECT -> handleManualConnect(intent)
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
        try {
            busy = false
            manualMode = false
            currentPort = -1
            currentHost = "127.0.0.1"
            connectEndpoint = null
            connectRequestActive = false
            connectLatch = java.util.concurrent.CountDownLatch(1)
            promoteForeground(searchingNotification())
            // 每次点击都【全新重建】发现器，而不是复用已在跑的实例：
            // ColorOS 16 实测同进程内长期运行/经历过 stop-restart 的 mDNS
            // 会话可能被系统静默挂死（零事件），只有新实例（新 HandlerThread +
            // 新 listener）注册时系统 mdns 才会把缓存中的配对服务立即推上来——
            // 这正是此前"第一次搜索转圈、第二次点才行"的真实生效路径，
            // 现在第一次点击就主动走这条路径
            discovery?.release()
            discovery = AdbDiscovery.Continuous(
                this,
                AdbDiscovery.TYPE_PAIRING,
                onValidPort = { port -> onPairingPortFound(port) },
                onLost = { onPairingServiceLost() }
            )
            discovery?.start()
        } catch (e: Exception) {
            // 任何启动异常都发失败通知，绝不能让服务静默死掉、通知栏空空如也
            Log.e(TAG, "配对服务启动失败", e)
            postResult(
                success = false,
                shortText = "配对服务启动失败，点通知上的「重试」",
                detail = e.message ?: e.javaClass.simpleName
            )
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    stopForeground(STOP_FOREGROUND_DETACH)
                }
            } catch (_: Exception) {
            }
            stopSelf()
        }
    }

    /**
     * 提升为前台服务：类型跟随 manifest（shortService，与 Shizuku 一致）。
     * 系统拒绝 FGS 启动（Android 12+ 后台限制）时退化为普通通知，
     * 至少让用户看到通知而不是毫无反馈。
     */
    private fun promoteForeground(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MANIFEST)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.e(TAG, "startForeground 失败，退化为普通通知", e)
            runCatching {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
            }
        }
    }

    private fun onPairingPortFound(port: Int) {
        if (busy || manualMode) return
        if (port == currentPort) return
        currentPort = port
        currentHost = "127.0.0.1"
        Log.i(TAG, "发现配对服务端口: $port")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, inputNotification(currentHost, port))
    }

    private fun onPairingServiceLost() {
        if (busy || manualMode) return
        currentPort = -1
        Log.i(TAG, "配对服务消失，恢复搜索状态通知")
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, searchingNotification())
    }

    // ---------------------------------------------------- 手动地址兜底（免 mDNS）

    /**
     * 用户通过通知栏手动回传系统配对页显示的「IP:端口」。
     * 走 getForegroundService 回传，先满足 Android 12+ 的 5 秒 startForeground 要求。
     */
    private fun handleManualAddr(intent: Intent) {
        promoteForeground(searchingNotification())
        if (busy) return
        val raw = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_ADDR)?.toString()?.trim().orEmpty()
        val parsed = parseHostPort(raw)
        if (parsed == null) {
            Log.w(TAG, "手动地址无法解析: \"$raw\"")
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, manualAddrErrorNotification(raw))
            return
        }
        val (host, port) = parsed
        manualMode = true
        currentHost = host
        currentPort = port
        Log.i(TAG, "使用手动配对地址: $host:$port，停止 mDNS 发现")
        discovery?.stop()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, inputNotification(host, port))
    }

    /**
     * 解析用户照抄的配对页地址。支持「192.168.1.5:37211」和「192.168.1.5 37211」
     * 两种写法（冒号/空格分隔）。系统配对页显示的一律是 IPv4，这里也只接受 IPv4，
     * 拒绝主机名/IPv6，避免随手输错后在 TLS 连接处白白等 10 秒超时。
     * @return host to port；格式不合法返回 null
     */
    private fun parseHostPort(input: String): Pair<String, Int>? {
        if (input.isBlank()) return null
        // 最后一个冒号或空白作为分隔；端口取末尾 2~5 位数字
        val match = Regex("^(.+?)[\\s:]+(\\d{2,5})$").find(input) ?: return null
        val host = match.groupValues[1].trim().removePrefix("http://").removePrefix("https://")
        val port = match.groupValues[2].toInt()
        val ipv4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")
            .find(host) ?: return null
        if (ipv4.groupValues.drop(1).any { it.toInt() > 255 }) return null
        if (port !in 1024..65535) return null
        return host to port
    }

    // ---------------------------------------- 第二阶段兜底：手动输入 connect 地址

    /**
     * 授权工作线程（pairAndGrant 内）在 mDNS 两轮找不到 connect 服务时调用：
     * 发通知请用户照抄无线调试【主页面】地址，阻塞等待回传或超时。
     */
    private val manualConnectProvider = AdbAuthManager.ManualConnectProvider {
        connectEndpoint = null
        connectLatch = java.util.concurrent.CountDownLatch(1)
        connectRequestActive = true
        promoteForeground(manualConnectNotification(null))
        val signalled = try {
            connectLatch.await(MANUAL_CONNECT_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            false
        }
        connectRequestActive = false
        val ep = connectEndpoint
        Log.i(TAG, "手动 connect 等待结束：signalled=$signalled endpoint=$ep")
        ep
    }

    /** 通知栏回传无线调试主页面地址；格式错误时刷新通知让用户重输，不解除等待 */
    private fun handleManualConnect(intent: Intent) {
        promoteForeground(workingNotification())
        if (!connectRequestActive) return
        val raw = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_CONNECT_ADDR)?.toString()?.trim().orEmpty()
        val parsed = parseHostPort(raw)
        if (parsed == null) {
            Log.w(TAG, "手动连接地址无法解析: \"$raw\"")
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, manualConnectNotification(raw))
            return
        }
        connectEndpoint = parsed
        connectRequestActive = false
        connectLatch.countDown()
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, workingNotification())
    }

    // ---------------------------------------------------------------- 输入回传

    private fun handleReply(intent: Intent) {
        // RemoteInput 的 PendingIntent 走 getForegroundService：若进程被杀后由回传 Intent
        // 单独重建服务，系统要求 5 秒内 startForeground，否则 Android 12+ 直接崩溃。
        promoteForeground(workingNotification())
        val code = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(KEY_CODE)?.toString()?.trim().orEmpty()
        val port = intent.getIntExtra(EXTRA_PORT, currentPort).takeIf { it > 0 } ?: currentPort
        val host = intent.getStringExtra(EXTRA_HOST)?.takeIf { it.isNotBlank() } ?: currentHost

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
                shortText = "未获取到配对端口，请重新打开配对页后点重试，或点「手动输入」照抄页面地址",
                detail = null
            )
            return
        }

        busy = true
        discovery?.stop()
        promoteForeground(workingNotification())

        thread(name = "adb-pair-grant") {
            try {
                // 完整闭环：配对 → 发现 connect 端口 → pm grant / shell 直写兜底 → 验证
                // host：自动发现时固定 127.0.0.1；手动兜底时为用户填写的局域网 IP
                // manualConnectProvider：mDNS 找不到 connect 服务时，通知栏向用户
                // 索取无线调试主页面地址（第二阶段兜底）
                val r = AdbAuthManager.pairAndGrant(
                    applicationContext, code, host, port, manualConnectProvider
                )
                postResult(
                    success = true,
                    shortText = if (r.shellDirect) "授权成功，无障碍服务已开启" else "授权成功，权限自动恢复已开启",
                    detail = null
                )
                finishAndStop(removeNotification = false)
            } catch (e: Exception) {
                Log.w(TAG, "配对授权失败", e)
                postResult(
                    success = false,
                    shortText = "配对或授权失败：可点「重试」再来一次；机型不支持时点「手动授权」改用传统方式",
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
            .setContentText("请打开系统「无线调试 → 使用配对码配对设备」页面；一直搜不到可点「手动输入」")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "1. 打开系统「无线调试 → 使用配对码配对设备」页面\n" +
                        "2. 页面上有 6 位配对码和「IP 地址和端口」（形如 192.168.1.5:37211）\n" +
                        "3. 一直搜不到时点下方「手动输入」照填地址端口，再输入配对码\n\n" +
                        "怕抄错？建议先用另一部手机把整个配对页拍下来，或把地址、端口、配对码写在纸上，对照输入"
                )
            )
            .addAction(manualAddrAction())
            .addAction(stopAction())
            .build()

    /** 手动地址解析失败时的提示通知：保留输入入口，允许用户照着重输 */
    private fun manualAddrErrorNotification(rawInput: String): Notification =
        baseBuilder()
            .setContentTitle("地址格式不正确，请重新输入")
            .setContentText(
                "请照抄系统配对页上「IP 地址和端口」那一整行，形如 192.168.1.5:37211" +
                    if (rawInput.isNotBlank()) "（刚才收到：\"$rawInput\"）" else ""
            )
            .setStyle(
                Notification.BigTextStyle()
                    .bigText(
                        "请照抄系统配对页上「IP 地址和端口」那一整行，形如 192.168.1.5:37211。\n" +
                            "注意：\n· IP 和端口之间用英文冒号或空格\n· 配对页必须保持显示，别退出\n" +
                            "· 建议先用另一部手机拍照，或写在纸上对照输入\n" +
                            if (rawInput.isNotBlank()) "刚才收到：\"$rawInput\"" else ""
                    )
            )
            .addAction(manualAddrAction())
            .addAction(stopAction())
            .build()

    private fun workingNotification(): Notification =
        baseBuilder()
            .setContentTitle("正在配对并授予权限…")
            .setContentText("请勿关闭系统无线调试页面")
            .build()

    private fun inputNotification(host: String, port: Int): Notification {
        val remoteInput = RemoteInput.Builder(KEY_CODE)
            .setLabel("输入 6 位配对码")
            .build()

        val replyIntent = Intent(this, AdbPairingService::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_PORT, port)
            .putExtra(EXTRA_HOST, host)
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
            .setContentTitle("请输入配对码")
            .setContentText("目标 $host:$port；保持系统配对页显示，点下方按钮输入 6 位码")
            .addAction(action)
            .addAction(manualAddrAction())
            .addAction(stopAction())
            .build()
    }

    /** 手动输入配对页「IP:端口」的通知动作（RemoteInput 直填，不拉起任何界面） */
    private fun manualAddrAction(): Notification.Action {
        val remoteInput = RemoteInput.Builder(KEY_ADDR)
            // 输入框内联提示文字不宜过长（部分 ROM 只显示一行）：
            // 完整的拍照/抄纸条说明放在通知展开正文里
            .setLabel("照填 IP:端口，如 192.168.1.5:37211")
            .build()

        val intent = Intent(this, AdbPairingService::class.java)
            .setAction(ACTION_MANUAL_ADDR)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        // getForegroundService：回传发生在 App 后台（系统设置页在前台），
        // Android 12+ 要求 5 秒内 startForeground
        val pi = PendingIntent.getForegroundService(this, 14, intent, flags)

        return Notification.Action.Builder(null, "手动输入", pi)
            .addRemoteInput(remoteInput)
            .build()
    }

    /**
     * 第二阶段通知：配对已成功，索取无线调试【主页面】的连接地址。
     * 必须强调"不是配对弹窗里的端口"，两处端口数字不同，用户极易抄混。
     */
    private fun manualConnectNotification(badRaw: String?): Notification =
        baseBuilder()
            .setContentTitle("配对成功，请输入连接地址（最后一步）")
            .setContentText("退出配对码弹窗，照抄「无线调试」主页面上的 IP 地址和端口")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    "配对已成功，还差最后一步：\n" +
                        "1. 点返回退出配对码弹窗，回到「无线调试」主页面\n" +
                        "2. 把主页面上显示的「IP 地址和端口」照填到下方输入框，形如 192.168.1.5:42117\n" +
                        "注意：这是主页面上的端口，和刚才配对弹窗里的端口【不是同一个】\n" +
                        "怕抄错？先用另一部手机把主页面拍下来，或写在纸上对照输入" +
                        if (!badRaw.isNullOrBlank()) "\n\n刚才收到的格式不对：\"$badRaw\"，请重新输入" else ""
                )
            )
            .addAction(manualConnectAction())
            .addAction(stopAction())
            .build()

    /** 手动输入无线调试主页面「IP:端口」的通知动作 */
    private fun manualConnectAction(): Notification.Action {
        val remoteInput = RemoteInput.Builder(KEY_CONNECT_ADDR)
            .setLabel("主页面 IP:端口，如 192.168.1.5:42117")
            .build()

        val intent = Intent(this, AdbPairingService::class.java)
            .setAction(ACTION_MANUAL_CONNECT)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pi = PendingIntent.getForegroundService(this, 15, intent, flags)

        return Notification.Action.Builder(null, "输入连接地址", pi)
            .addRemoteInput(remoteInput)
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

    /** 兜底动作：放弃无线配对，回 App 走传统手动授权（手动开无障碍+录屏）。
     *  用 Activity PendingIntent（通知动作触发的启动有后台启动豁免），
     *  extra 由 MainActivity 写入一次性 manual_fallback 标记 */
    private fun manualAction(): Notification.Action {
        val pi = PendingIntent.getActivity(
            this, 13,
            Intent(this, MainActivity::class.java)
                .setAction(ACTION_MANUAL_ACTIVITY)
                .putExtra(EXTRA_MANUAL_FALLBACK, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Action.Builder(null, "手动授权", pi).build()
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
            builder.addAction(manualAction())
            builder.addAction(stopAction())
        }
        val notification = builder.build()
        lastResultNotification = notification
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun finishAndStop(removeNotification: Boolean) {
        discovery?.stop()
        // 用户取消/服务收尾时解除第二阶段手动输入的阻塞等待（endpoint 保持 null）
        connectRequestActive = false
        connectLatch.countDown()
        try {
            if (!removeNotification && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // DETACH：结果通知脱离前台服务生命周期保留在通知栏（本服务实际只在 API30+ 运行）
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        } catch (e: Exception) {
            Log.w(TAG, "stopForeground failed", e)
        }
        // 旧系统兜底：重新补发一次结果通知
        if (!removeNotification && Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            lastResultNotification?.let {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, it)
            }
        }
        stopSelf()
    }

    @Volatile
    private var lastResultNotification: Notification? = null

    override fun onDestroy() {
        discovery?.release()
        discovery = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
