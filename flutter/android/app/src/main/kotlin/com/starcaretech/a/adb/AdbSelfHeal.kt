package com.starcaretech.a.adb

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import com.starcaretech.a.InputService

/**
 * 无障碍自愈监控（进程内）：
 * - App 进程启动即开始（[com.starcaretech.a.MainApplication]），周期性检查无障碍状态
 * - 双信号判断：
 *    1. 系统名单 ENABLED_ACCESSIBILITY_SERVICES 里是否有我们（[AdbAuthManager.isAccessibilityListed]）
 *    2. 服务实际是否在运行（[InputService.isOpen]，onServiceConnected/onDestroy 维护）
 * - 名单缺失 → 直接写回；名单在但服务未绑定（国产 ROM 常见的"开关开着但不工作"）
 *   → [AdbAuthManager.forceRebindAccessibility] 移除→延时→加回，强制系统重绑
 * - 该授权只在 App 进程存活期间才有意义：进程被强杀时进入 STOPPED 状态，
 *   任何进程内手段（含本监控）都无法运行；进程重启后（开机/用户点击）会立刻再检查
 * - 仅 Android 11+ 生效（无线调试配对的前提）
 */
object AdbSelfHeal {

    private const val TAG = "AdbSelfHeal"
    private const val INTERVAL_MS = 60_000L

    /** 连续 N 次检测到"名单在但服务未绑定"才强制重绑，避开进程刚启动时的绑定延迟 */
    private const val UNBOUND_THRESHOLD = 2

    /** 强制重绑冷却：重绑后系统重新绑定需要时间，防止反复抖动 */
    private const val FORCE_REBIND_COOLDOWN_MS = 5 * 60_000L

    private val thread = HandlerThread("AdbSelfHeal")
    private lateinit var handler: Handler

    @Volatile
    private var started = false

    /** 连续检测到"名单在但未绑定"的次数 */
    private var unboundStreak = 0

    /** 上次强制重绑时间（0 表示从未执行） */
    private var lastForceRebindAt = 0L

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
                    checkOnce(appContext)
                } catch (e: Exception) {
                    Log.w(TAG, "自愈检查异常", e)
                }
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
        // 进程启动 5s 后先跑一次（避开启动高峰期），之后按周期执行
        handler.postDelayed(check, 5_000)
    }

    private fun checkOnce(context: Context) {
        if (!AdbAuthManager.isWriteSecureSettingsGranted(context)) return

        val listed = AdbAuthManager.isAccessibilityListed(context)
        val bound = InputService.isOpen

        when {
            // 正常：服务在运行。若名单异常缺失（理论上不该发生）仍补写
            bound -> {
                unboundStreak = 0
                if (!listed) AdbAuthManager.repairAccessibility(context)
            }
            // 名单缺失 → 直接写回，系统会绑定并拉起 InputService
            !listed -> {
                unboundStreak = 0
                Log.i(TAG, "InputService 不在无障碍名单，执行写回")
                AdbAuthManager.repairAccessibility(context)
            }
            // 名单在、服务没在跑 → 疑似被系统解绑，连续确认后强制重绑
            else -> {
                unboundStreak++
                val now = System.currentTimeMillis()
                if (unboundStreak >= UNBOUND_THRESHOLD &&
                    now - lastForceRebindAt > FORCE_REBIND_COOLDOWN_MS
                ) {
                    Log.w(TAG, "InputService 连续 $unboundStreak 次未绑定但名单存在，执行强制重绑")
                    if (AdbAuthManager.forceRebindAccessibility(context)) {
                        lastForceRebindAt = now
                    }
                    unboundStreak = 0
                } else {
                    Log.d(TAG, "InputService 未绑定（第 $unboundStreak 次），暂不强制重绑")
                }
            }
        }
    }
}
