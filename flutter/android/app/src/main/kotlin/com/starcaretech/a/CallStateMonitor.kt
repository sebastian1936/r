package com.starcaretech.a

import android.content.Context
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.starcaretech.a.adb.HarmonyOsDetector
import ffi.FFI

/**
 * 防电诈：检测系统蜂窝通话状态（无需任何权限）。
 *
 * 原理：轮询 AudioManager.getMode() —— 该接口读取不需要 READ_PHONE_STATE：
 * - MODE_IN_CALL(2)：蜂窝通话中（拨号/接通）→ 锁定
 * - MODE_RINGTONE(1)：多数机型来电响铃阶段 → 锁定（响铃即锁）
 * - MODE_IN_COMMUNICATION(3)：微信/QQ 等 VoIP → 不锁（只防系统电话）
 * - MODE_NORMAL(0)：空闲 → 按用户原开关恢复
 *
 * 状态经 FFI.setCallInputLocked 通知 Rust 硬门控输入注入，
 * 同时经 on_call_state_changed 通知 Dart 置灰开关。
 *
 * 仅 Android 11+ 非鸿蒙启用：低版本/鸿蒙挂断后无法自动恢复输入权限。
 */
object CallStateMonitor {
    private const val TAG = "CallStateMonitor"
    private const val POLL_INTERVAL_MS = 2000L

    private var started = false
    private var handler: Handler? = null
    private var audioManager: AudioManager? = null
    private var lastLocked: Boolean? = null

    private val pollTask = object : Runnable {
        override fun run() {
            val am = audioManager ?: return
            applyState(am.mode)
            handler?.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    fun start(ctx: Context) {
        if (started) return
        // 仅 Android 11+ 非鸿蒙：低版本/鸿蒙无无线调试一键授权，
        // 挂断后无法自动恢复无障碍输入权限，不启用本机制
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || HarmonyOsDetector.isHarmonyOs) {
            return
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            Log.w(TAG, "AudioManager unavailable")
            return
        }
        audioManager = am
        handler = Handler(Looper.getMainLooper())
        started = true
        // 立即对齐一次（服务在通话中被看门狗重启时也能马上锁住）
        applyState(am.mode)
        handler?.postDelayed(pollTask, POLL_INTERVAL_MS)
        Log.i(TAG, "call state monitor started (audio mode polling)")
    }

    fun stop() {
        if (!started) return
        handler?.removeCallbacks(pollTask)
        handler = null
        audioManager = null
        started = false
        // 注销即按空闲恢复，避免服务销毁后 Rust 侧残留锁
        applyState(AudioManager.MODE_NORMAL)
        Log.i(TAG, "call state monitor stopped")
    }

    private fun applyState(audioMode: Int) {
        val locked = when (audioMode) {
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_RINGTONE -> true
            // MODE_IN_COMMUNICATION 是 VoIP（微信/QQ 等），不锁
            else -> false
        }
        if (lastLocked == locked) return
        lastLocked = locked
        Log.i(TAG, "audio mode=$audioMode, input locked=$locked")
        // Rust：硬门控所有输入注入 + 实时下发在线连接权限
        FFI.setCallInputLocked(locked)
        // Dart：置灰本地"输入控制"开关
        MainActivity.flutterMethodChannel?.invokeMethod(
            "on_call_state_changed",
            mapOf("locked" to locked)
        )
    }
}
