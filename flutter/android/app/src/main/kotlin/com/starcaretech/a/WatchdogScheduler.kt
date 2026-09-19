package com.starcaretech.a

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity

/**
 * 进程外看门狗：MIUI 等 ROM 锁屏久了会把整个进程杀掉，进程内的
 * PARTIAL_WAKE_LOCK/Alarm 随之全部失效。系统级的拉起锚点：
 *  1. [WatchdogJobService]：JobScheduler 周期任务（15 分钟，persisted，
 *     进程死亡/重启手机后仍由 system_server 持有与触发）
 *  2. InputService（无障碍）：系统会为分发事件重新拉起被禁用组件所在进程，
 *     其 onServiceConnected/周期巡检里调用 [ensureServiceRunning]
 *  3. BootReceiver：开机且用户期望在线时拉起
 *
 * 拉起判定：[KEY_SERVICE_WANTED]=true（仅在用户主动关服 destroy() 时清掉）。
 */
object WatchdogScheduler {
    private const val TAG = "Watchdog"
    private const val JOB_ID = 0x5744 // "WD"
    private const val PERIOD_MS = 15L * 60 * 1000
    private const val FLEX_MS = 5L * 60 * 1000
    /** 同一进程内两次尝试拉起服务的最小间隔，避免锚点密集时反复启动 */
    private const val MIN_START_INTERVAL_MS = 30_000L

    @Volatile
    private var lastStartAttemptMs = 0L

    fun isServiceWanted(context: Context): Boolean =
        context.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
            .getBoolean(KEY_SERVICE_WANTED, false)

    fun setServiceWanted(context: Context, wanted: Boolean) {
        context.getSharedPreferences(KEY_SHARED_PREFERENCES, FlutterActivity.MODE_PRIVATE)
            .edit().putBoolean(KEY_SERVICE_WANTED, wanted).apply()
        Log.i(TAG, "service wanted=$wanted")
        if (wanted) scheduleJob(context) else cancelJob(context)
    }

    fun scheduleJob(context: Context) {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        val builder = JobInfo.Builder(
            JOB_ID,
            ComponentName(context, WatchdogJobService::class.java)
        )
            // 不设网络约束：离线也要能拉起本地被控服务
            .setPersisted(true) // 重启手机后保留（需 RECEIVE_BOOT_COMPLETED，已声明）
            .setRequiresCharging(false)
            .setRequiresDeviceIdle(false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            builder.setPeriodic(PERIOD_MS, FLEX_MS)
        } else {
            builder.setPeriodic(PERIOD_MS)
        }
        runCatching {
            val r = js.schedule(builder.build())
            Log.i(TAG, "watchdog job scheduled result=$r")
        }.onFailure { Log.w(TAG, "schedule watchdog job fail", it) }
    }

    fun cancelJob(context: Context) {
        val js = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as? JobScheduler ?: return
        runCatching { js.cancel(JOB_ID) }
    }

    /**
     * 进程外锚点（Job/无障碍/开机）调用：用户期望在线且 MainService 未存活时拉起。
     * 走 ACT_TRY_RESTORE_MEDIA_PROJECTION：服务 onCreate 完成信令初始化，
     * onStartCommand 静默恢复录屏，失败发恢复通知（含自动点击/熔断保护）。
     */
    fun ensureServiceRunning(context: Context, source: String) {
        if (MainService.isServiceAlive) return
        if (!isServiceWanted(context)) {
            Log.i(TAG, "ensure skip (not wanted) from=$source")
            return
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastStartAttemptMs < MIN_START_INTERVAL_MS) {
            Log.d(TAG, "ensure throttled from=$source")
            return
        }
        lastStartAttemptMs = now
        Log.i(TAG, "拉起 MainService（来源=$source）")
        val intent = Intent(context, MainService::class.java).apply {
            action = ACT_TRY_RESTORE_MEDIA_PROJECTION
        }
        runCatching {
            ContextCompat.startForegroundService(context, intent)
            scheduleJob(context)
        }.onFailure {
            // Android 12+ 后台启动 FGS 被拒等：保留任务，等下一个锚点/亮屏再试
            Log.w(TAG, "startForegroundService fail from=$source", it)
        }
    }
}
