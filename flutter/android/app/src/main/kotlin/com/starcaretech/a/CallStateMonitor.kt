package com.starcaretech.a

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.starcaretech.a.adb.HarmonyOsDetector
import ffi.FFI

/**
 * 防电诈：监听系统电话（蜂窝通话）状态。
 *
 * - RINGING（响铃，含来电等待）/ OFFHOOK（摘机：拨号中 + 通话中）→ 锁定被控输入
 * - IDLE（挂断/空闲）→ 按用户原开关恢复
 *
 * 仅系统电话，不检测微信/QQ 等 VoIP 通话。
 * 状态经 FFI.setCallInputLocked 通知 Rust 硬门控输入注入，
 * 同时经 on_call_state_changed 通知 Dart 置灰开关。
 */
object CallStateMonitor {
    private const val TAG = "CallStateMonitor"

    const val PERMISSION = Manifest.permission.READ_PHONE_STATE

    private var registered = false
    private var listener: PhoneStateListener? = null
    private var lastLocked: Boolean? = null

    @Suppress("DEPRECATION")
    fun start(ctx: Context) {
        if (registered) return
        // 仅 Android 11+ 非鸿蒙：低版本/鸿蒙无无线调试一键授权，
        // 挂断后无法自动恢复无障碍输入权限，不启用本机制
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || HarmonyOsDetector.isHarmonyOs) {
            return
        }
        val granted = ContextCompat.checkSelfPermission(ctx, PERMISSION) ==
                PackageManager.PERMISSION_GRANTED
        if (!granted) {
            Log.i(TAG, "READ_PHONE_STATE not granted, monitor disabled")
            return
        }
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        if (tm == null) {
            Log.w(TAG, "TelephonyManager unavailable")
            return
        }
        val l = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                applyState(state)
            }
        }
        runCatching {
            tm.listen(l, PhoneStateListener.LISTEN_CALL_STATE)
            listener = l
            registered = true
            // 服务在通话中被（看门狗）重启时，立即对齐当前状态
            applyState(tm.callState)
            Log.i(TAG, "call state monitor started")
        }.onFailure { Log.w(TAG, "listen failed", it) }
    }

    @Suppress("DEPRECATION")
    fun stop(ctx: Context) {
        if (!registered) return
        val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
        listener?.let { l -> runCatching { tm?.listen(l, PhoneStateListener.LISTEN_NONE) } }
        listener = null
        registered = false
        // 注销即按空闲恢复，避免服务销毁后 Rust 侧残留锁
        applyState(TelephonyManager.CALL_STATE_IDLE)
        Log.i(TAG, "call state monitor stopped")
    }

    private fun applyState(state: Int) {
        val locked = when (state) {
            TelephonyManager.CALL_STATE_RINGING,
            TelephonyManager.CALL_STATE_OFFHOOK -> true
            else -> false
        }
        if (lastLocked == locked) return
        lastLocked = locked
        Log.i(TAG, "phone call state=$state, input locked=$locked")
        // Rust：硬门控所有输入注入 + 实时下发在线连接权限
        FFI.setCallInputLocked(locked)
        // Dart：置灰本地"输入控制"开关
        MainActivity.flutterMethodChannel?.invokeMethod(
            "on_call_state_changed",
            mapOf("locked" to locked)
        )
    }
}
