package com.lchuang.xiaozhimobile

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import com.lchuang.xiaozhimobile.conversation.AssistantState
import com.lchuang.xiaozhimobile.conversation.AssistantStateStore
import com.lchuang.xiaozhimobile.conversation.AssistantStateStoreProvider

class AssistantOverlayController(
    context: Context,
    val stateStore: AssistantStateStore = AssistantStateStoreProvider.instance(),
) {
    private val appContext = context.applicationContext
    private val windowManager = appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val density = appContext.resources.displayMetrics.density

    @Volatile private var overlayView: AssistantOverlayView? = null
    @Volatile private var onExitRequested: (() -> Unit)? = null
    private val stateObserver: (AssistantState) -> Unit = { state ->
        mainHandler.post { overlayView?.let { renderAssistantState(it, state) } }
    }
    init {
        stateStore.addObserver(stateObserver)
    }

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
            renderAssistantState(view, stateStore.current)
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
        val viewState = stateStore.applyLegacyOverlayState(state)
        mainHandler.post { overlayView?.setConversationState(viewState) }
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
        stateStore.removeObserver(stateObserver)
        hide()
        onExitRequested = null
    }

    private fun dp(value: Float): Float = value * density
}

internal sealed interface AssistantOverlayRender {
    data class LegacyState(val state: ConversationState) : AssistantOverlayRender
    data object Confirmation : AssistantOverlayRender {
        val legacyState: ConversationState = ConversationState.IDLE_WAKE
    }
}

internal fun AssistantState.toOverlayRender(): AssistantOverlayRender = when (this) {
    AssistantState.WAITING_WAKE -> AssistantOverlayRender.LegacyState(ConversationState.IDLE_WAKE)
    AssistantState.LISTENING -> AssistantOverlayRender.LegacyState(ConversationState.LISTENING)
    AssistantState.RECOGNIZING -> AssistantOverlayRender.LegacyState(ConversationState.RECOGNIZING)
    AssistantState.EXECUTING -> AssistantOverlayRender.LegacyState(ConversationState.EXECUTING)
    AssistantState.SPEAKING -> AssistantOverlayRender.LegacyState(ConversationState.SPEAKING)
    AssistantState.WAITING_CONFIRMATION -> AssistantOverlayRender.Confirmation
}

private fun renderAssistantState(view: AssistantOverlayView, state: AssistantState) {
    when (val render = state.toOverlayRender()) {
        is AssistantOverlayRender.LegacyState -> view.setConversationState(render.state)
        AssistantOverlayRender.Confirmation -> {
            view.setConversationState(AssistantOverlayRender.Confirmation.legacyState)
            view.setContent(title = "需要你的确认", status = "等待确认…")
        }
    }
}

internal fun AssistantStateStore.applyLegacyOverlayState(state: ConversationState): ConversationState {
    when (state) {
        ConversationState.IDLE_WAKE -> onConversationEnded()
        ConversationState.LISTENING -> onAudioCaptureStarted()
        ConversationState.RECOGNIZING -> onAudioCaptureStopped()
        ConversationState.EXECUTING -> onExecutionStarted()
        ConversationState.SPEAKING -> onTtsStarted()
        ConversationState.READY_TO_LISTEN -> onWakeDetected()
        ConversationState.EXITING -> Unit
    }
    return state
}
