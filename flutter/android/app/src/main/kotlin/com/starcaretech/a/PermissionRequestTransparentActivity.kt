package com.starcaretech.a

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 开机全自动恢复（实验）：由 boot 自动流程在锁屏之上拉起本页时，
 * 30s 内没等到授权结果就自毁——开机早期 SystemUI/无障碍可能尚未就绪，
 * 自毁释放闸门后 MainService 会在 45s/90s 补弹全新一轮。
 * 用户手点通知进入（不带 EXT_BOOT_AUTO_CONSENT）不启用，永不替用户关窗。
 */
private const val BOOT_CONSENT_AUTO_FINISH_MS = 30_000L

/**
 * 等待本页成为有焦点的顶级窗口（解散锁屏动画/前台切换完成）后再弹系统框；
 * 焦点回调万一丢失，该兜底超时后也照样发起，避免永久挂起。
 */
private const val FOCUS_WAIT_FALLBACK_MS = 2_000L

/**
 * requestDismissKeyguard 在个别 ROM 上可能任何回调都不返回，
 * 该超时后回退旧路径（锁屏之上直弹+30s 自毁补弹），不死等。
 */
private const val DISMISS_FALLBACK_MS = 8_000L

/**
 * 发起系统录屏确认框的透明页。
 *
 * 单实例 + 全局闸门双保险（修复锁屏恢复后"点一个又一个"的弹窗风暴）：
 * - manifest launchMode=singleTask，重复启动只复用已存在实例；
 * - onCreate 必须先抢到 MainService 的全局授权闸门，抢不到直接 finish，
 *   杜绝亮屏 receiver/看门狗/多 startId 叠出多个确认框。
 *
 * 本页可能的来源：
 * 1. MainService 在 App 前台且亮屏解锁时自动拉起（已过门控）；
 * 2. 用户主动点击恢复通知（锁屏/后台场景的唯一入口，属用户行为）；
 * 3. 开机全自动恢复（实验）：无安全锁屏设备在锁屏之上被自动拉起，
 *    带 EXT_BOOT_AUTO_CONSENT，30s 无结果自毁以便服务补弹。
 */
class PermissionRequestTransparentActivity: Activity() {
    private val logTag = "permissionRequest"

    private val autoFinishHandler = Handler(Looper.getMainLooper())
    private val bootAutoFinish = Runnable {
        Log.i(logTag, "开机锁屏自动授权：${BOOT_CONSENT_AUTO_FINISH_MS}ms 内无结果，自毁以放行补弹")
        // onDestroy 会兜底 endConsentWait + endProjectionRequest，闸门随之释放
        finish()
    }

    /** 窗口焦点迟迟不来时兜底发起，避免解散锁屏后卡在本页不弹框 */
    private val focusFallback = Runnable {
        if (launchPendingFocus && !captureLaunched) {
            Log.i(logTag, "等待窗口焦点 ${FOCUS_WAIT_FALLBACK_MS}ms 超时，直接发起录屏确认框")
            launchCaptureIntent()
        }
    }

    /** requestDismissKeyguard 长时间无任何回调时回退旧路径 */
    private val dismissFallback = Runnable {
        if (!captureLaunched && !launchPendingFocus) {
            Log.i(logTag, "解散锁屏 ${DISMISS_FALLBACK_MS}ms 无回调，回退锁屏之上直弹")
            scheduleCaptureAfterFocus()
        }
    }

    /** 授权结果已交给 MainService 处理，闸门由服务端释放；否则本页销毁时自行释放 */
    private var handedOffToService = false

    /** 系统录屏确认框是否已发起（防焦点回调/兜底超时重复发起） */
    private var captureLaunched = false

    /** 已请求解散锁屏/等待成为顶级前台，窗口拿到焦点后再发起 */
    private var launchPendingFocus = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(logTag, "onCreate PermissionRequestTransparentActivity: intent.action: ${intent.action}")

        if (intent.action != ACT_REQUEST_MEDIA_PROJECTION) {
            Log.i(logTag, "action 非法，关闭透明页")
            finish()
            return
        }
        // 兜底防御：服务已持有有效录屏会话时（残留全屏通知误触、国产 ROM
        // 对全屏 Intent 的重复展开、多触发源重复 startActivity），绝不重弹
        // 系统确认框——重弹会新建 MediaProjection 顶掉正在进行的被控采集，
        // 并把整个 App 带到前台（MIUI/OriginOS 上表现为"一被远程就弹前台"）。
        if (MainService.hasActiveProjection()) {
            Log.i(logTag, "录屏会话已存活，忽略重复的授权请求，直接关闭透明页")
            com.starcaretech.a.adb.AdbAuthManager.trace(
                applicationContext, "录屏透明页：会话已存活，跳过重复弹窗直接 finish")
            finish()
            return
        }
        if (!MainService.beginProjectionRequest()) {
            // 已有授权请求在途（重复实例/重入触发）：不做任何事直接关掉
            Log.i(logTag, "授权请求已在途，关闭重复的透明页")
            com.starcaretech.a.adb.AdbAuthManager.trace(
                applicationContext, "录屏透明页：闸门未抢到，直接 finish")
            finish()
            return
        }
        com.starcaretech.a.adb.AdbAuthManager.trace(
            applicationContext, "录屏透明页：开始授权流程（等待自动点击闸门 20s）")

        // 锁屏恢复场景：点亮屏幕并把本页显示在锁屏之上（无密码锁屏随后会用
        // requestDismissKeyguard 解散；有密码锁屏仍需先解锁，这是系统安全边界）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        val bootAutoConsent = intent.getBooleanExtra(EXT_BOOT_AUTO_CONSENT, false)
        if (bootAutoConsent) {
            Log.i(logTag, "开机自动授权：${BOOT_CONSENT_AUTO_FINISH_MS}ms 无结果将自毁")
            autoFinishHandler.postDelayed(bootAutoFinish, BOOT_CONSENT_AUTO_FINISH_MS)
        }

        // HyperOS Android16 实测：setShowWhenLocked/setTurnScreenOn 只作用于本页，
        // 不会传递给 startActivityForResult 拉起的系统 MediaProjection 确认
        // Activity——确认框不显示在锁屏之上，无障碍点击器无框可点；解锁瞬间
        // 挂起的请求还会被系统直接 resultCode=0 取消。对无密码（滑动/无）
        // 锁屏，官方 requestDismissKeyguard 可无需手势/凭据直接代码解散，
        // 在 onKeyguardGone（解锁后的真前台）再发起确认框即可正常显示并被
        // 自动点击。有安全锁屏时回调立即取消，走旧路径（锁屏之上直弹）兜底。
        val keyguardManager =
            getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        if (bootAutoConsent &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            keyguardManager?.isKeyguardLocked == true
        ) {
            try {
                Log.i(logTag, "开机自动授权：锁屏中，先请求代码解散无密码锁屏")
                autoFinishHandler.postDelayed(dismissFallback, DISMISS_FALLBACK_MS)
                keyguardManager.requestDismissKeyguard(
                    this,
                    object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onKeyguardGone() {
                            Log.i(logTag, "锁屏已解散（onKeyguardGone），准备在真前台发起录屏确认框")
                            scheduleCaptureAfterFocus()
                        }

                        override fun onDismissCancelled() {
                            // 有安全锁屏需凭据（开机武装本不该放行到此，双保险）
                            Log.i(logTag, "解散锁屏被取消（可能需要凭据），回退锁屏之上直弹")
                            scheduleCaptureAfterFocus()
                        }

                        override fun onDismissError() {
                            Log.w(logTag, "解散锁屏出错，回退锁屏之上直弹")
                            scheduleCaptureAfterFocus()
                        }
                    }
                )
                return
            } catch (e: Exception) {
                Log.w(logTag, "requestDismissKeyguard 异常，回退锁屏之上直弹", e)
            }
        }

        scheduleCaptureAfterFocus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // 解散锁屏动画/前台切换完成、本页成为有焦点的顶级窗口后，
        // Android14+ 要求的"发起方可见前台"条件才真正满足，
        // 避免 USER_PRESENT 沿上 229ms 即被系统瞬拒。
        if (hasFocus && launchPendingFocus && !captureLaunched) {
            launchCaptureIntent()
        }
    }

    /**
     * 等本页拿到窗口焦点后再发起系统录屏确认框；焦点回调可能丢失，
     * [FOCUS_WAIT_FALLBACK_MS] 兜底超时直接发起。
     */
    private fun scheduleCaptureAfterFocus() {
        if (captureLaunched || isFinishing) return
        autoFinishHandler.removeCallbacks(dismissFallback)
        launchPendingFocus = true
        if (hasWindowFocus()) {
            launchCaptureIntent()
        } else {
            autoFinishHandler.removeCallbacks(focusFallback)
            autoFinishHandler.postDelayed(focusFallback, FOCUS_WAIT_FALLBACK_MS)
        }
    }

    private fun launchCaptureIntent() {
        if (captureLaunched || isFinishing) return
        captureLaunched = true
        launchPendingFocus = false
        autoFinishHandler.removeCallbacks(focusFallback)

        // 点击闸门在确认框真正发起时才开：解散锁屏/等焦点的耗时不吞 20s TTL
        InputService.beginConsentWait()

        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        // Android 14+ 默认弹"整个屏幕/单个应用"两段式选择框（多一步且
        // 无障碍自动点选标签在 HyperOS 等 ROM 上不可靠）。按官方文档用
        // createConfigForDefaultDisplay() 明确声明只投整个显示屏：
        // 系统跳过应用/整屏选择器，直接回到老样式的"立即开始/取消"
        // 单确认框，无障碍自动点击即可重新生效。
        // 最终一次"立即开始"用户确认是系统安全边界，无法用 API 跳过；
        // 忽略本 config 仍弹选择器的 ROM，由 InputService 的
        // hasProjectionChoice 兜底检测到后放弃自动点击，交回用户手动。
        val captureIntent =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mediaProjectionManager.createScreenCaptureIntent(
                    MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                mediaProjectionManager.createScreenCaptureIntent()
            }
        try {
            startActivityForResult(captureIntent, REQ_REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.w(logTag, "拉起系统录屏确认框失败，结束本轮授权", e)
            com.starcaretech.a.adb.AdbAuthManager.trace(
                applicationContext, "录屏透明页：拉起系统确认框异常 ${e.javaClass.simpleName}")
            InputService.endConsentWait()
            MainService.endProjectionRequest()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        autoFinishHandler.removeCallbacks(bootAutoFinish)
        if (requestCode == REQ_REQUEST_MEDIA_PROJECTION) {
            com.starcaretech.a.adb.AdbAuthManager.trace(
                applicationContext,
                "录屏确认框结果: resultCode=$resultCode data=${data != null}")
            // 系统确认框已有结果，立即关闭闸门
            InputService.endConsentWait()
            if (resultCode == RESULT_OK && data != null) {
                // 授权成功：闸门保持到 MainService 处理完 ACT_INIT 再释放，
                // 防止处理间隙其它触发源又弹一个
                handedOffToService = true
                launchService(data)
            } else {
                setResult(RES_FAILED)
                MainService.endProjectionRequest()
            }
        }

        finish()
    }

    override fun onDestroy() {
        autoFinishHandler.removeCallbacks(bootAutoFinish)
        autoFinishHandler.removeCallbacks(focusFallback)
        autoFinishHandler.removeCallbacks(dismissFallback)
        // 用户没完成授权页面就被销毁（系统回收/按返回/开机自毁超时）：兜底释放闸门
        if (!handedOffToService) {
            InputService.endConsentWait()
            MainService.endProjectionRequest()
        }
        super.onDestroy()
    }

    private fun launchService(mediaProjectionResultIntent: Intent) {
        Log.d(logTag, "Launch MainService")
        val serviceIntent = Intent(this, MainService::class.java)
        serviceIntent.action = ACT_INIT_MEDIA_PROJECTION_AND_SERVICE
        serviceIntent.putExtra(EXT_MEDIA_PROJECTION_RES_INTENT, mediaProjectionResultIntent)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

}
