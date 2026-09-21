package com.starcaretech.a

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import kotlin.concurrent.thread

/**
 * 看门狗周期任务：进程被杀后由 system_server 在 Job 触发时重建本进程，
 * 检查用户是否仍期望服务在线，是则拉起 MainService。
 * 周期 15 分钟（系统下限），Doze 下在维护窗口执行。
 */
class WatchdogJobService : JobService() {

    companion object {
        private const val TAG = "WatchdogJob"
    }

    override fun onStartJob(params: JobParameters?): Boolean {
        Log.i(TAG, "onStartJob")
        thread {
            runCatching {
                // 服务存活也补一次保活心跳（救被 MIUI 挂起的信令长连），
                // 不存活才拉起
                WatchdogScheduler.onPeriodicAnchor(applicationContext, "job")
            }
            // 周期性任务无需重试调度；即使本次启动被系统拒绝，下个周期还会来
            jobFinished(params, false)
        }
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        // 被系统提前结束（如约束变化）：要求重新排期
        return true
    }
}
