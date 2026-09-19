package com.starcaretech.a

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.util.Log

class PermissionRequestTransparentActivity: Activity() {
    private val logTag = "permissionRequest"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(logTag, "onCreate PermissionRequestTransparentActivity: intent.action: ${intent.action}")

        // 锁屏恢复场景：点亮屏幕并把授权页显示在锁屏之上（无密码锁屏可直接完成；
        // 有密码锁屏仍需先解锁，这是系统安全边界）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        when (intent.action) {
            ACT_REQUEST_MEDIA_PROJECTION -> {
                // 打开自动点击闸门：仅接下来 20 秒内、系统录屏确认窗口出现时，
                // 才允许本应用无障碍服务点击"立即开始"
                InputService.beginConsentWait()
                val mediaProjectionManager =
                    getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                val intent = mediaProjectionManager.createScreenCaptureIntent()
                startActivityForResult(intent, REQ_REQUEST_MEDIA_PROJECTION)
            }
            else -> finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_REQUEST_MEDIA_PROJECTION) {
            // 系统确认框已有结果，立即关闭闸门
            InputService.endConsentWait()
            if (resultCode == RESULT_OK && data != null) {
                launchService(data)
            } else {
                setResult(RES_FAILED)
            }
        }

        finish()
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