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

    @Volatile private var overlayView: AssistantOverlayView? = null

    fun canDraw(): Boolean = Settings.canDrawOverlays(appContext)

    fun show() {
        if (!canDraw()) return
        mainHandler.post {
            if (overlayView != null || !canDraw()) return@post
            val view = AssistantOverlayView(appContext)
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
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
    }
}
