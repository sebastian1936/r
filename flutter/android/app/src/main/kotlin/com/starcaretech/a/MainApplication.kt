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
