package com.starcaretech.a

import android.app.Application
import android.util.Log
import ffi.FFI
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
        FFI.onAppStart(applicationContext)
        // ADB 授权后的无障碍自愈监控（仅 Android 11+ 实际生效）
        AdbSelfHeal.start(applicationContext)
    }
}
