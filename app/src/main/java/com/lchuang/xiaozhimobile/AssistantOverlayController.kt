package com.lchuang.xiaozhimobile

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager

class AssistantOverlayController(context: Context) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = appContext.resources.displayMetrics.density

    @Volatile private var overlayView: AssistantOverlayView? = null
    @Volatile private var onExitRequested: (() -> Unit)? = null
    @Volatile private var conversationState: ConversationState = ConversationState.IDLE_WAKE

    fun canDraw(): Boolean = Settings.canDrawOverlays(appContext)

    fun setOnExitRequested(callback: (() -> Unit)?) {
        onExitRequested = callback
    }

    fun show() {
        if (!canDraw()) return
        mainHandler.post {
            if (overlayView != null || !canDraw()) return@post
            val metrics = appContext.resources.displayMetrics
            val panelWidth = (metrics.widthPixels * 0.88f).toInt()
            val panelHeight = dp(225f).toInt()
            val topOffset = (metrics.heightPixels * 0.20f).toInt()
            val view = AssistantOverlayView(appContext) {
                onExitRequested?.invoke()
            }
            view.setConversationState(conversationState)
            val params = WindowManager.LayoutParams(
                panelWidth,
                panelHeight,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = topOffset
            }
            try {
                windowManager.addView(view, params)
                overlayView = view
            } catch (_: Throwable) {
                overlayView = null
            }
        }
    }

    fun update(title: String, status: String, heard: String = "") {
        if (!canDraw()) return
        show()
        mainHandler.post {
            overlayView?.setContent(title, status, heard)
        }
    }

    fun updateState(state: ConversationState) {
        conversationState = state
        mainHandler.post { overlayView?.setConversationState(state) }
    }

    fun updateAudioLevel(level: Float) {
        mainHandler.post {
            overlayView?.setAudioLevel(level.coerceIn(0f, 1f))
        }
    }

    fun hide() {
        mainHandler.post {
            val view = overlayView ?: return@post
            try {
                windowManager.removeView(view)
            } catch (_: Throwable) {
            } finally {
                overlayView = null
            }
        }
    }

    fun release() {
        hide()
        onExitRequested = null
    }

    private fun dp(value: Float): Float = value * density
}
