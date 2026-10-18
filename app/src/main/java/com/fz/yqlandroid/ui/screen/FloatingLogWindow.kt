package com.fz.yqlandroid.ui.screen

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.fz.yqlandroid.manager.DebugLog
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 系统级日志悬浮窗（需「显示在其他应用上层」权限）：退到桌面/别的 App 也显示。
 * 小圆钮可拖动，点开为日志面板，每 2s 刷新。
 */
object FloatingLogWindow {

    private const val REFRESH_MS = 2000L

    private val main = Handler(Looper.getMainLooper())
    private var wm: WindowManager? = null
    private var bubble: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var panel: View? = null
    private var logText: TextView? = null
    private var logScroll: ScrollView? = null

    fun canDraw(context: Context) =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)

    fun requestPermission(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            Log.w(DebugLog.TAG, "[诊断] 打开悬浮窗权限页失败: ${e.message}")
        }
    }

    val isShowing get() = bubble != null || panel != null

    fun show(context: Context) {
        if (isShowing || !canDraw(context)) return
        val app = context.applicationContext
        wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        showBubble(app)
    }

    fun hide() {
        main.removeCallbacks(refreshTask)
        listOf(bubble, panel).forEach { v -> v?.let { try { wm?.removeView(it) } catch (_: Exception) {} } }
        bubble = null
        panel = null
        logText = null
        logScroll = null
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun dp(context: Context, v: Float) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility")
    private fun showBubble(context: Context) {
        val size = dp(context, 52f)
        val view = TextView(context).apply {
            text = "日志"
            setTextColor(Color.WHITE)
            textSize = 14f
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(0xCC222222.toInt())
            }
        }
        val params = WindowManager.LayoutParams(
            size, size, overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = context.resources.displayMetrics.widthPixels - size
            y = context.resources.displayMetrics.heightPixels / 3
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        view.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    params.x = startX + dx.toInt()
                    params.y = startY + dy.toInt()
                    try { wm?.updateViewLayout(v, params) } catch (_: Exception) {}
                }
                MotionEvent.ACTION_UP -> if (!moved) openPanel(context)
            }
            true
        }
        try {
            wm?.addView(view, params)
            bubble = view
            bubbleParams = params
        } catch (e: Exception) {
            Log.w(DebugLog.TAG, "[诊断] 显示悬浮窗失败: ${e.message}")
        }
    }

    private fun openPanel(context: Context) {
        if (panel != null) return
        bubble?.visibility = View.GONE
        val pad = dp(context, 6f)

        fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            setTextColor(0xFF65AEF7.toInt())
            textSize = 14f
            setPadding(pad * 2, pad * 2, pad * 2, pad * 2)
            setOnClickListener { onClick() }
        }

        val text = TextView(context).apply {
            setTextColor(0xFFDDDDDD.toInt())
            textSize = 9f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(false)
        }
        val scroll = ScrollView(context).apply { addView(text) }
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button("复制") { copyAll(context) })
            addView(button("清空") {
                thread { DebugLog.clear(); main.post { refreshNow(forceBottom = true) } }
            })
            addView(button("收起") { closePanel() })
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            background = GradientDrawable().apply {
                cornerRadius = dp(context, 10f).toFloat()
                setColor(0xE6111111.toInt())
            }
            addView(bar)
            addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        val dm = context.resources.displayMetrics
        val params = WindowManager.LayoutParams(
            dm.widthPixels - dp(context, 16f), (dm.heightPixels * 0.6f).toInt(), overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(context, 40f)
        }
        try {
            wm?.addView(root, params)
            panel = root
            logText = text
            logScroll = scroll
            refreshNow(forceBottom = true)
        } catch (e: Exception) {
            Log.w(DebugLog.TAG, "[诊断] 打开日志面板失败: ${e.message}")
            bubble?.visibility = View.VISIBLE
        }
    }

    private fun closePanel() {
        main.removeCallbacks(refreshTask)
        panel?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        panel = null
        logText = null
        logScroll = null
        bubble?.visibility = View.VISIBLE
    }

    private val refreshTask = Runnable { refreshNow(forceBottom = false) }

    private fun refreshNow(forceBottom: Boolean) {
        main.removeCallbacks(refreshTask)
        val text = logText ?: return
        val scroll = logScroll ?: return
        val atBottom = scroll.getChildAt(0)?.let { scroll.scrollY + scroll.height >= it.height - 40 } ?: true
        text.text = collapseRepeats(DebugLog.recentLines()).joinToString("\n")
        if (forceBottom || atBottom) scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
        main.postDelayed(refreshTask, REFRESH_MS)
    }

    private fun copyAll(context: Context) {
        thread {
            val text = buildCopyText(DebugLog.readLines())
            main.post { copyToClipboard(context, text) }
        }
    }
}
