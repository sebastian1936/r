package com.starcaretech.a

import android.app.Application
import android.util.Log
import ffi.FFI

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
    }
}
