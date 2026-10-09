package com.starcaretech.a

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import ffi.FFI
import com.starcaretech.a.adb.AdbAuthManager
import com.starcaretech.a.adb.AdbSelfHeal

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"

        /**
         * 本应用是否有 Activity 处于前台（started）。
         * 看门狗拉起/开机恢复时进程里没有任何 Activity（false），
         * 此时绝不能主动 startActivity 弹录屏确认框（后台 Activity + 锁屏
         * 会叠出多个透明授权页，是弹窗风暴/崩溃的入口）。
         */
        @JvmStatic
        @Volatile
        var isAppForeground: Boolean = false
            private set
    }

    override fun onCreate() {
        // 必须最先安装：后续任何阶段（含 FFI native 初始化）卡住主线程，
        // 心跳看门狗都能把"最后阶段"写进诊断日志
        StartWatchdog.install(this)
        StartWatchdog.stage("Application.onCreate: enter（super 前）")
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var startedCount = 0

            override fun onActivityStarted(activity: android.app.Activity) {
                startedCount++
                isAppForeground = startedCount > 0
            }

            override fun onActivityStopped(activity: android.app.Activity) {
                if (startedCount > 0) startedCount--
                isAppForeground = startedCount > 0
            }

            override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
            override fun onActivityResumed(activity: android.app.Activity) {}
            override fun onActivityPaused(activity: android.app.Activity) {}
            override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
            override fun onActivityDestroyed(activity: android.app.Activity) {}
        })
        // 崩溃日志落盘：Java 异常栈/logcat 写 filesDir/crash，供重启后弹窗分享定位
        CrashLogger.install(this)
        Log.d(TAG, "App start")
        // 主控/被控双包拆分：controller 包注入 conn-type=outgoing，
        // 使 is_outgoing_only() 生效，隐藏被控入口且不初始化被控服务
        if (BuildConfig.FLAVOR == "controller") {
            FFI.setHardOption("conn-type", "outgoing")
        }
        StartWatchdog.stage("Application: FFI.onAppStart 前")
        FFI.onAppStart(applicationContext)
        StartWatchdog.stage("Application: FFI.onAppStart 后")
        // ADB 授权后的无障碍自愈监控（仅 Android 11+ 实际生效）
        AdbSelfHeal.start(applicationContext)
        StartWatchdog.stage("Application.onCreate: done")
    }
}

/**
 * 冷启动分阶段打点 + 主线程心跳看门狗（诊断用）。
 *
 * 背景：划掉任务后系统先以 stub 进程拉起 InputService，看门狗随即拉起
 * MainService，用户再点图标时 Activity 冷启动要与已在跑的 native 初始化
 * 争主线程/native 锁，偶发卡在 Flutter 引擎起来之前（界面一直显示启动
 * 窗口转圈，且没有任何 Flutter 日志）。
 *
 * 原理：主线程每秒跑一次心跳；后台线程每 3s 检查，心跳停跳超过 4s 即
 * 判定主线程卡死，并把 [stage] 记录的最后阶段落盘——即使界面彻底卡死，
 * 下次打开诊断日志也能直接看到停在哪一步。
 */
object StartWatchdog {
    private const val BEAT_INTERVAL_MS = 1000L
    private const val CHECK_INTERVAL_MS = 3000L
    private const val STALL_THRESHOLD_MS = 4000L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var lastBeatMs = 0L

    @Volatile
    private var lastStage = "unknown"

    /** 同一次卡顿只报一条，恢复后复位，避免日志被刷屏 */
    @Volatile
    private var stallReported = false

    fun install(app: Application) {
        appContext = app.applicationContext
        lastBeatMs = SystemClock.elapsedRealtime()
        mainHandler.post(object : Runnable {
            override fun run() {
                lastBeatMs = SystemClock.elapsedRealtime()
                mainHandler.postDelayed(this, BEAT_INTERVAL_MS)
            }
        })
        Thread({
            while (true) {
                try {
                    Thread.sleep(CHECK_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val age = SystemClock.elapsedRealtime() - lastBeatMs
                if (age >= STALL_THRESHOLD_MS) {
                    if (!stallReported) {
                        stallReported = true
                        appContext?.let {
                            AdbAuthManager.trace(
                                it,
                                "MAIN-THREAD-ANCHOR ★主线程已 ${age}ms 无响应，最后阶段=$lastStage"
                            )
                        }
                    }
                } else {
                    stallReported = false
                }
            }
        }, "startup-watchdog").start()
    }

    /** 记录一个启动阶段：落盘一行 STAGE 日志，并更新看门狗的"最后阶段" */
    fun stage(label: String) {
        lastStage = label
        appContext?.let {
            AdbAuthManager.trace(it, "STAGE $label（up=${SystemClock.elapsedRealtime()}ms）")
        }
    }
}
