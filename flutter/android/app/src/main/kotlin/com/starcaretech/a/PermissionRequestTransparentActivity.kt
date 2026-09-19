package com.starcaretech.a

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

/**
 * 发起系统录屏确认框的透明页。
 *
 * 单实例 + 全局闸门双保险（修复锁屏恢复后"点一个又一个"的弹窗风暴）：
 * - manifest launchMode=singleTask，重复启动只复用已存在实例；
 * - onCreate 必须先抢到 MainService 的全局授权闸门，抢不到直接 finish，
 *   杜绝亮屏 receiver/看门狗/多 startId 叠出多个确认框。
 *
 * 本页只可能由两个来源启动：
 * 1. MainService 在 App 前台且亮屏解锁时自动拉起（已过门控）；
 * 2. 用户主动点击恢复通知（锁屏/后台场景的唯一入口，属用户行为）。
 */
class PermissionRequestTransparentActivity: Activity() {
    private val logTag = "permissionRequest"

    /** 授权结果已交给 MainService 处理，闸门由服务端释放；否则本页销毁时自行释放 */
    private var handedOffToService = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(logTag, "onCreate PermissionRequestTransparentActivity: intent.action: ${intent.action}")

        if (intent.action != ACT_REQUEST_MEDIA_PROJECTION
            || !MainService.beginProjectionRequest()) {
            // 已有授权请求在途（重复实例/重入触发）：不做任何事直接关掉
            Log.i(logTag, "授权请求已在途或 action 非法，关闭重复的透明页")
            finish()
            return
        }

        // 锁屏恢复场景：点亮屏幕并把授权页显示在锁屏之上（无密码锁屏可直接完成；
        // 有密码锁屏仍需先解锁，这是系统安全边界）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        // 打开自动点击闸门：仅接下来 20 秒内、系统录屏确认窗口出现时，
        // 才允许本应用无障碍服务点击"立即开始"
        InputService.beginConsentWait()
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = mediaProjectionManager.createScreenCaptureIntent()
        startActivityForResult(intent, REQ_REQUEST_MEDIA_PROJECTION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_REQUEST_MEDIA_PROJECTION) {
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
        // 用户没完成授权页面就被销毁（系统回收/按返回）：兜底释放闸门
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
