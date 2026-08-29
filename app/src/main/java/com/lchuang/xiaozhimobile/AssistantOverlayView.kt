package com.lchuang.xiaozhimobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

class AssistantOverlayView(
    context: Context,
    private val onExitRequested: () -> Unit = {}
) : View(context) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(178, 5, 16, 45) }
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(115, 93, 177, 255)
    }
    private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(7f); color = Color.argb(150, 44, 164, 255)
        setShadowLayer(dp(15f), 0f, 0f, Color.argb(210, 59, 130, 246))
    }
    private val ringBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND; color = Color.rgb(57, 189, 255)
    }
    private val ringPurple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(4f); strokeCap = Paint.Cap.ROUND; color = Color.rgb(170, 82, 255)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER; textSize = sp(21f) }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 210, 225, 255); textAlign = Paint.Align.CENTER; textSize = sp(13.5f) }
    private val heardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(100, 213, 255); textAlign = Paint.Align.CENTER; textSize = sp(12.5f) }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = dp(2.4f); strokeCap = Paint.Cap.ROUND; color = Color.rgb(78, 180, 255) }
    private val closePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 225, 235, 255); textAlign = Paint.Align.CENTER; textSize = sp(24f) }

    private var title = "你好，有什么可以帮你？"
    private var status = "我在听…"
    private var heard = ""
    private var audioLevel = 0.12f
    private var conversationState = ConversationState.IDLE_WAKE
    private val closeHitRect = RectF()
    private var exitEmitted = false

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean {
            requestExitOnce()
            return true
        }
    })

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = false
    }

    fun setContent(title: String, status: String, heard: String = "") {
        this.title = title; this.status = status; this.heard = heard; invalidate()
    }

    fun setConversationState(value: ConversationState) {
        conversationState = value
        if (value != ConversationState.LISTENING) audioLevel = 0.08f
        invalidate()
    }

    fun setAudioLevel(level: Float) {
        if (conversationState == ConversationState.LISTENING) audioLevel = level.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP && closeHitRect.contains(event.x, event.y)) {
            requestExitOnce()
            return true
        }
        return gestureDetector.onTouchEvent(event) || super.onTouchEvent(event)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        val margin = dp(2f)
        val panel = RectF(margin, margin, width - margin, height - margin)
        canvas.drawRoundRect(panel, dp(28f), dp(28f), panelPaint)
        canvas.drawRoundRect(panel, dp(28f), dp(28f), panelStroke)

        val closeCx = panel.right - dp(25f)
        val closeCy = panel.top + dp(25f)
        closeHitRect.set(closeCx - dp(22f), closeCy - dp(22f), closeCx + dp(22f), closeCy + dp(22f))
        canvas.drawText("×", closeCx, closeCy + dp(8f), closePaint)

        val now = SystemClock.uptimeMillis()
        val phase = (now % 2200L) / 2200f
        val activePulse = if (conversationState == ConversationState.LISTENING) 0.055f else 0.025f
        val pulse = 1f + activePulse * sin(phase.toDouble() * 2.0 * PI).toFloat()
        val cx = width / 2f
        val cy = panel.top + dp(47f)
        val r = dp(24f) * pulse
        val ring = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawCircle(cx, cy, r, ringGlow)
        canvas.drawArc(ring, -85f, 205f, false, ringBlue)
        canvas.drawArc(ring, 120f, 210f, false, ringPurple)

        val titleY = panel.top + dp(98f)
        canvas.drawText(title, cx, titleY, titlePaint)
        canvas.drawText(status, cx, titleY + dp(28f), statusPaint)
        if (heard.isNotBlank()) {
            val clipped = if (heard.length > 30) heard.take(30) + "…" else heard
            canvas.drawText(clipped, cx, titleY + dp(50f), heardPaint)
        }

        val waveY = panel.bottom - dp(28f)
        val waveLeft = panel.left + dp(48f)
        val waveRight = panel.right - dp(48f)
        val bars = 31
        val gap = (waveRight - waveLeft) / (bars - 1)
        val effectiveLevel = if (conversationState == ConversationState.LISTENING) audioLevel else 0.08f
        val base = 0.16f + effectiveLevel * 0.84f
        for (i in 0 until bars) {
            val x = waveLeft + i * gap
            val wave = kotlin.math.abs(sin((i * 0.72f + phase * 12f).toDouble())).toFloat()
            val heightPx = dp(5f) + dp(18f) * base * (0.35f + 0.65f * wave)
            val fraction = i.toFloat() / (bars - 1).coerceAtLeast(1)
            wavePaint.color = blendColor(Color.rgb(161, 70, 255), Color.rgb(46, 195, 255), fraction)
            canvas.drawLine(x, waveY - heightPx / 2f, x, waveY + heightPx / 2f, wavePaint)
        }
        if (conversationState != ConversationState.EXITING) postInvalidateDelayed(40L)
    }

    private fun requestExitOnce() {
        if (exitEmitted) return
        exitEmitted = true
        onExitRequested()
    }

    private fun blendColor(start: Int, end: Int, fraction: Float): Int {
        val f = fraction.coerceIn(0f, 1f)
        val r = (Color.red(start) + (Color.red(end) - Color.red(start)) * f).toInt()
        val g = (Color.green(start) + (Color.green(end) - Color.green(start)) * f).toInt()
        val b = (Color.blue(start) + (Color.blue(end) - Color.blue(start)) * f).toInt()
        return Color.rgb(r, g, b)
    }

    private fun dp(v: Float): Float = v * density
    private fun sp(v: Float): Float = v * scaledDensity
}
