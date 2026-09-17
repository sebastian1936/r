package com.starcaretech.a.adb

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

/**
 * 无障碍自愈监控（进程内）：
 * - App 进程启动即开始（[MainApplication]），周期性检查无障碍服务是否被系统解绑，
 *   解绑则用 WRITE_SECURE_SETTINGS 写回
 * - 该授权只在 App 进程存活期间才有意义：进程被杀时远程服务同样不存在；
 *   进程重启后（开机/用户点击）会立刻再检查一次
 * - 仅 Android 11+ 生效（无线调试配对的前提）
 */
object AdbSelfHeal {

    private const val TAG = "AdbSelfHeal"
    private const val INTERVAL_MS = 60_000L

    private val thread = HandlerThread("AdbSelfHeal")
    private lateinit var handler: Handler

    @Volatile
    private var started = false

    fun start(context: Context) {
        if (started) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        started = true
        thread.start()
        handler = Handler(thread.looper)

        val appContext = context.applicationContext
        val check = object : Runnable {
            override fun run() {
                try {
                    if (AdbAuthManager.isWriteSecureSettingsGranted(appContext)) {
                        AdbAuthManager.repairAccessibility(appContext)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "自愈检查异常", e)
                }
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        // 进程启动 5s 后先跑一次（避开启动高峰期），之后按周期执行
        handler.postDelayed(check, 5_000)
    }
}
