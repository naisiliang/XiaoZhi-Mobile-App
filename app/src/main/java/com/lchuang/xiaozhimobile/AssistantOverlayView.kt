package com.lchuang.xiaozhimobile

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import kotlin.math.PI
import kotlin.math.sin

class AssistantOverlayView(context: Context) : View(context) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity

    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(178, 5, 16, 45)
    }
    private val panelStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = Color.argb(115, 93, 177, 255)
    }
    private val ringGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(7f)
        color = Color.argb(150, 44, 164, 255)
        setShadowLayer(dp(15f), 0f, 0f, Color.argb(210, 59, 130, 246))
    }
    private val ringBlue = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(57, 189, 255)
    }
    private val ringPurple = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(4f)
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(170, 82, 255)
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = sp(21f)
    }
    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(225, 210, 225, 255)
        textAlign = Paint.Align.CENTER
        textSize = sp(13.5f)
    }
    private val heardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(100, 213, 255)
        textAlign = Paint.Align.CENTER
        textSize = sp(12.5f)
    }
    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(2.4f)
        strokeCap = Paint.Cap.ROUND
        color = Color.rgb(78, 180, 255)
    }

    private var title: String = "你好，有什么可以帮你？"
    private var status: String = "我在听…"
    private var heard: String = ""
    private var audioLevel: Float = 0.12f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = false
        isFocusable = false
    }

    fun setContent(title: String, status: String, heard: String = "") {
        this.title = title
        this.status = status
        this.heard = heard
        invalidate()
    }

    fun setAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return

        val panelWidth = width * 0.88f
        val panelHeight = dp(205f)
        val left = (width - panelWidth) / 2f
        val top = height * 0.20f
        val panel = RectF(left, top, left + panelWidth, top + panelHeight)
        canvas.drawRoundRect(panel, dp(28f), dp(28f), panelPaint)
        canvas.drawRoundRect(panel, dp(28f), dp(28f), panelStroke)

        val now = SystemClock.uptimeMillis()
        val phase = (now % 2200L) / 2200f
        val pulse = 1f + 0.055f * sin(phase.toDouble() * 2.0 * PI).toFloat()
        val cx = width / 2f
        val cy = top + dp(47f)
        val r = dp(24f) * pulse
        val rect = RectF(cx - r, cy - r, cx + r, cy + r)

        canvas.drawCircle(cx, cy, r, ringGlow)
        canvas.drawArc(rect, -85f, 205f, false, ringBlue)
        canvas.drawArc(rect, 120f, 210f, false, ringPurple)

        val titleY = top + dp(98f)
        canvas.drawText(title, cx, titleY, titlePaint)
        canvas.drawText(status, cx, titleY + dp(28f), statusPaint)
        if (heard.isNotBlank()) {
            val clipped = if (heard.length > 30) heard.take(30) + "…" else heard
            canvas.drawText(clipped, cx, titleY + dp(50f), heardPaint)
        }

        val waveY = top + panelHeight - dp(28f)
        val waveLeft = left + dp(48f)
        val waveRight = panel.right - dp(48f)
        val bars = 31
        val gap = (waveRight - waveLeft) / (bars - 1)
        val base = 0.16f + audioLevel * 0.84f
        for (i in 0 until bars) {
            val x = waveLeft + i * gap
            val wave = kotlin.math.abs(sin((i * 0.72f + phase * 12f).toDouble())).toFloat()
            val shape = 0.35f + 0.65f * wave
            val heightPx = dp(5f) + dp(18f) * base * shape
            val fraction = i.toFloat() / (bars - 1).coerceAtLeast(1)
            wavePaint.color = blendColor(Color.rgb(161, 70, 255), Color.rgb(46, 195, 255), fraction)
            canvas.drawLine(x, waveY - heightPx / 2f, x, waveY + heightPx / 2f, wavePaint)
        }

        postInvalidateDelayed(40L)
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
