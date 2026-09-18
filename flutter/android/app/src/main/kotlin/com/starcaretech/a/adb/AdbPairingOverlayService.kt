package com.starcaretech.a.adb

import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * 系统悬浮窗配对：浮在配对码页面上方，用户无需切屏即可填码提交。
 * 需要 SYSTEM_ALERT_WINDOW 权限（Manifest 已声明）。
 */
class AdbPairingOverlayService : Service() {

    companion object {
        @Volatile
        var onResult: ((success: Boolean, message: String) -> Unit)? = null
    }

    private val V = LinearLayout.VERTICAL
    private val H = LinearLayout.HORIZONTAL
    private val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    private var rootView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (rootView == null) showOverlay()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeOverlay()
        super.onDestroy()
    }

    private fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics
    ).toInt()

    private fun roundBg(color: Int, radius: Int = 4): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }

    private fun showOverlay() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val ctx = this

        val container = LinearLayout(ctx).apply {
            orientation = V
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = roundBg(0xF0222222.toInt(), 12)
        }

        // 标题
        container.addView(TextView(ctx).apply {
            text = "无线调试配对"
            setTextColor(Color.WHITE); textSize = 16f
            setPadding(0, 0, 0, dp(8))
        })
        // 提示
        container.addView(TextView(ctx).apply {
            text = "保持配对页前台，在下方填入\n端口号和配对码后点确定"
            setTextColor(0xCCCCCC); textSize = 11f
            setPadding(0, 0, 0, dp(12))
        })

        // 端口
        container.addView(TextView(ctx).apply {
            text = "配对端口"; setTextColor(0xAAAAAA); textSize = 12f
        })
        val portEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "如 43251"
            setTextColor(Color.WHITE); setHintTextColor(0x666666); textSize = 13f
            background = roundBg(0x33FFFFFF)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }
        }
        container.addView(portEdit)

        // 配对码
        container.addView(TextView(ctx).apply {
            text = "配对码（6 位）"; setTextColor(0xAAAAAA); textSize = 12f
        })
        val codeEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6 位数字"
            setTextColor(Color.WHITE); setHintTextColor(0x666666); textSize = 13f
            background = roundBg(0x33FFFFFF)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        container.addView(codeEdit)

        // 错误
        val errorText = TextView(ctx).apply {
            setTextColor(0xFF6666); textSize = 12f
            setPadding(0, dp(6), 0, dp(6)); visibility = View.GONE
        }
        container.addView(errorText)

        // loading 行
        val progress = ProgressBar(ctx).apply {
            val lp = LinearLayout.LayoutParams(dp(18), dp(18))
            layoutParams = lp
        }
        val statusText = TextView(ctx).apply {
            setTextColor(0xAAAAAA); textSize = 12f
            setPadding(dp(6), 0, 0, 0)
        }
        val busyRow = LinearLayout(ctx).apply {
            orientation = H; gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
        }
        busyRow.addView(progress)
        busyRow.addView(statusText)
        container.addView(busyRow)

        // 按钮行
        val btnRow = LinearLayout(ctx).apply {
            orientation = H
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) }
        }
        val cancelBtn = Button(ctx).apply {
            text = "取消"; textSize = 12f; setTextColor(Color.WHITE)
            background = roundBg(0x44FFFFFF)
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f).apply { rightMargin = dp(8) }
        }
        val okBtn = Button(ctx).apply {
            text = "确定"; textSize = 12f; setTextColor(Color.WHITE)
            background = roundBg(0xFF4CAF50.toInt())
            layoutParams = LinearLayout.LayoutParams(0, dp(36), 1f)
        }
        btnRow.addView(cancelBtn); btnRow.addView(okBtn)
        container.addView(btnRow)

        // 宽度
        container.layoutParams = LinearLayout.LayoutParams(dp(280), WRAP_CONTENT)

        // 确定
        okBtn.setOnClickListener {
            val code = codeEdit.text.toString().trim()
            val port = portEdit.text.toString().trim().toIntOrNull()
            if (code.length != 6) {
                errorText.text = "请输入 6 位配对码"
                errorText.visibility = View.VISIBLE; return@setOnClickListener
            }
            if (port == null || port <= 0) {
                errorText.text = "请输入配对端口"
                errorText.visibility = View.VISIBLE; return@setOnClickListener
            }
            errorText.visibility = View.GONE
            okBtn.isEnabled = false; cancelBtn.isEnabled = false
            portEdit.isEnabled = false; codeEdit.isEnabled = false
            busyRow.visibility = View.VISIBLE
            statusText.text = "正在配对并授权…"

            thread {
                try {
                    AdbAuthManager.pairAndGrant(this@AdbPairingOverlayService, code, "127.0.0.1", port)
                    handler.post {
                        busyRow.visibility = View.GONE
                        Toast.makeText(this@AdbPairingOverlayService, "授权成功", Toast.LENGTH_SHORT).show()
                        onResult?.invoke(true, "授权成功，权限自动恢复已开启")
                        removeOverlay(); stopSelf()
                    }
                } catch (e: Exception) {
                    handler.post {
                        busyRow.visibility = View.GONE
                        okBtn.isEnabled = true; cancelBtn.isEnabled = true
                        portEdit.isEnabled = true; codeEdit.isEnabled = true
                        errorText.text = e.message ?: "授权失败，请重试"
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
        }

        cancelBtn.setOnClickListener {
            removeOverlay(); stopSelf()
            onResult?.invoke(false, "用户取消")
        }

        // 添加浮窗
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(80)
        }
        wm.addView(container, params)
        rootView = container

        // 弹键盘
        portEdit.requestFocus()
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(portEdit, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun removeOverlay() {
        rootView?.let { runCatching { wm.removeView(it) } }
        rootView = null
    }
}
