package com.starcaretech.a

import android.app.Application
import android.util.Log
import ffi.FFI
import com.starcaretech.a.adb.AdbSelfHeal

class MainApplication : Application() {
    companion object {
        private const val TAG = "MainApplication"
    }

    override fun onCreate() {
        super.onCreate()
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
