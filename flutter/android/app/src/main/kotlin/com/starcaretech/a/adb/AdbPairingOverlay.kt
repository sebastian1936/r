package com.starcaretech.a.adb

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
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
 * 配对悬浮窗 UI 构建器。
 * 窗口由 InputService（无障碍服务）以 TYPE_ACCESSIBILITY_OVERLAY 添加，
 * 这样可覆盖系统设置页面（普通 TYPE_APPLICATION_OVERLAY 在 Android 12+
 * 无法覆盖无线调试等敏感系统页面）。
 */
object AdbPairingOverlay {

    private const val V = LinearLayout.VERTICAL
    private const val H = LinearLayout.HORIZONTAL
    private const val MATCH = LinearLayout.LayoutParams.MATCH_PARENT
    private const val WRAP = LinearLayout.LayoutParams.WRAP_CONTENT

    fun dp(ctx: Context, v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics
    ).toInt()

    private fun roundBg(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radius.toFloat() }

    /**
     * 构建悬浮窗 View，并通过 wm 添加到屏幕。
     * @return 添加后的 root View，调用方负责 removeView
     */
    fun show(ctx: Context, wm: WindowManager): View {
        val handler = Handler(Looper.getMainLooper())

        val container = LinearLayout(ctx).apply {
            orientation = V
            setPadding(dp(ctx, 20), dp(ctx, 20), dp(ctx, 20), dp(ctx, 20))
            background = roundBg(0xF0222222.toInt(), dp(ctx, 12))
        }

        container.addView(TextView(ctx).apply {
            text = "无线调试配对"
            setTextColor(Color.WHITE); textSize = 16f
            setPadding(0, 0, 0, dp(ctx, 8))
        })
        container.addView(TextView(ctx).apply {
            text = "1. 系统「开发者选项 → 无线调试」\n" +
                    "2. 点「使用配对码配对设备」\n" +
                    "3. 页面上有一行「IP:端口」，抄到第一框\n" +
                    "4. 页面上的 6 位配对码，抄到第二框"
            setTextColor(0xCCCCCC); textSize = 11f
            setPadding(0, 0, 0, dp(ctx, 12))
        })

        container.addView(TextView(ctx).apply {
            text = "IP:端口"; setTextColor(0xAAAAAA); textSize = 12f
        })
        val addrEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "如 192.168.1.10:43251"
            setTextColor(Color.WHITE); setHintTextColor(0x666666); textSize = 13f
            background = roundBg(0x33FFFFFF, dp(ctx, 4))
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(ctx, 8) }
        }
        container.addView(addrEdit)

        container.addView(TextView(ctx).apply {
            text = "6 位配对码"; setTextColor(0xAAAAAA); textSize = 12f
        })
        val codeEdit = EditText(ctx).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "6 位数字"
            setTextColor(Color.WHITE); setHintTextColor(0x666666); textSize = 13f
            background = roundBg(0x33FFFFFF, dp(ctx, 4))
            setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
        }
        container.addView(codeEdit)

        val errorText = TextView(ctx).apply {
            setTextColor(Color.WHITE); textSize = 14f
            background = roundBg(0xCCFF3333.toInt(), dp(ctx, 6))
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply {
                topMargin = dp(ctx, 6); bottomMargin = dp(ctx, 4)
            }
        }
        container.addView(errorText)

        val progress = ProgressBar(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 18), dp(ctx, 18))
        }
        val statusText = TextView(ctx).apply {
            setTextColor(0xAAAAAA); textSize = 12f
            setPadding(dp(ctx, 6), 0, 0, 0)
        }
        val busyRow = LinearLayout(ctx).apply {
            orientation = H; gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 8) }
        }
        busyRow.addView(progress)
        busyRow.addView(statusText)
        container.addView(busyRow)

        val btnRow = LinearLayout(ctx).apply {
            orientation = H
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(ctx, 10) }
        }
        val cancelBtn = Button(ctx).apply {
            text = "取消"; textSize = 12f; setTextColor(Color.WHITE)
            background = roundBg(0x44FFFFFF, dp(ctx, 4))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { rightMargin = dp(ctx, 8) }
        }
        val okBtn = Button(ctx).apply {
            text = "确定"; textSize = 12f; setTextColor(Color.WHITE)
            background = roundBg(0xFF4CAF50.toInt(), dp(ctx, 4))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f)
        }
        btnRow.addView(cancelBtn); btnRow.addView(okBtn)
        container.addView(btnRow)

        container.layoutParams = LinearLayout.LayoutParams(dp(ctx, 280), WRAP)

        val remove = {
            runCatching { wm.removeView(container) }
            AdbAuthManager.pairingOverlayCallback = null
        }

        okBtn.setOnClickListener {
            val code = codeEdit.text.toString().trim()
            val addr = addrEdit.text.toString().trim()
            // 解析 IP:端口（支持粘贴的整行地址）
            val port = try {
                val parts = addr.split(':')
                parts.last().toInt()
            } catch (_: Exception) { null }
            if (code.length != 6) {
                errorText.text = "请输入 6 位配对码"
                errorText.visibility = View.VISIBLE; return@setOnClickListener
            }
            if (port == null || port <= 0) {
                errorText.text = "请输入正确的 IP:端口，如 192.168.1.10:43251"
                errorText.visibility = View.VISIBLE; return@setOnClickListener
            }
            errorText.visibility = View.GONE
            okBtn.isEnabled = false; cancelBtn.isEnabled = false
            addrEdit.isEnabled = false; codeEdit.isEnabled = false
            busyRow.visibility = View.VISIBLE
            statusText.text = "正在配对并授权…"

            thread {
                try {
                    // host 强制 127.0.0.1：本机连本机，不用局域网 IP
                    AdbAuthManager.pairAndGrant(ctx, code, "127.0.0.1", port)
                    handler.post {
                        busyRow.visibility = View.GONE
                        Toast.makeText(ctx, "授权成功", Toast.LENGTH_SHORT).show()
                        AdbAuthManager.pairingOverlayCallback?.invoke(true, "授权成功，权限自动恢复已开启")
                        remove()
                    }
                } catch (e: Exception) {
                    handler.post {
                        busyRow.visibility = View.GONE
                        okBtn.isEnabled = true; cancelBtn.isEnabled = true
                        addrEdit.isEnabled = true; codeEdit.isEnabled = true
                        val sb = StringBuilder(e.message ?: "授权失败")
                        var cause: Throwable? = e.cause
                        while (cause != null) {
                            sb.append("\n→ ").append(cause.message ?: cause.javaClass.simpleName)
                            cause = cause.cause
                        }
                        errorText.text = sb.toString()
                        errorText.visibility = View.VISIBLE
                    }
                }
            }
        }

        cancelBtn.setOnClickListener {
            AdbAuthManager.pairingOverlayCallback?.invoke(false, "用户取消")
            remove()
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(ctx, 80)
        }
        // 需要输入焦点才能弹键盘，单独设置
        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        wm.addView(container, params)
        rootView = container

        addrEdit.requestFocus()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .showSoftInput(addrEdit, InputMethodManager.SHOW_IMPLICIT)

        return container
    }

    @Volatile
    var rootView: View? = null
}
