package com.starcaretech.a

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * 现代 API 亮屏兜底：废弃的 SCREEN_BRIGHT_WAKELOCK 在部分 Android 15/16 ROM
 * 上 acquire 成功但屏不亮，用 setTurnScreenOn 的窗口绘制事件兜底。
 * 只点亮到锁屏页：绝不 dismissKeyguard/绕过密码（系统安全边界）。
 * 由 MainService 仅在息屏且 wakelock 300ms 复查无效时拉起，10s 节流。
 */
class WakeScreenActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }
    }

    override fun onResume() {
        super.onResume()
        // 等 turn-screen-on 的窗口绘制事件发出后自毁，用户基本无感知
        Handler(Looper.getMainLooper()).postDelayed({ finish() }, 800)
    }
}
