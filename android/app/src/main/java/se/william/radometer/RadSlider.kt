package se.william.radometer

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * Reglaget: ett brett fält tvärs över skärmen som man drar i, eller trycker
 * någonstans på för att hoppa dit. Ritat för hand — en vanlig SeekBar går inte
 * att få så här stor och grön utan att slåss med temat.
 */
class RadSlider(context: Context) : View(context) {

    var value: Float = 0f
        private set

    var onValueChanged: ((Float) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * d
        color = 0xFF0D5F21.toInt()
    }
    private val tickPaint = Paint().apply {
        color = 0x2B2BFF5B
        strokeWidth = 1f * d
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbGlow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x552BFF5B
    }

    private val track = RectF()
    private val thumb = RectF()
    private val thumbW = 30f * d
    private val radius = 4f * d

    init {
        isClickable = true
        trackPaint.color = 0xFF061C0E.toInt()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val inset = thumbW / 2f
        track.set(inset, 8f * d, w - inset, h - 8f * d)
        fillPaint.shader = LinearGradient(
            track.left, 0f, track.right, 0f,
            intArrayOf(0x2B2BFF5B, 0x2BFFC21A, 0x38FF4230),
            floatArrayOf(0f, 0.62f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRoundRect(track, radius, radius, trackPaint)
        canvas.drawRoundRect(track, radius, radius, fillPaint)

        // Skalstreck, som på en analog panel
        var x = track.left
        val step = track.width() / 40f
        while (x < track.right) {
            canvas.drawLine(x, track.top + 6f * d, x, track.bottom - 6f * d, tickPaint)
            x += step
        }
        canvas.drawRoundRect(track, radius, radius, borderPaint)

        // Greppet
        val cx = track.left + value * track.width()
        thumb.set(cx - thumbW / 2f, 0f, cx + thumbW / 2f, height.toFloat())
        canvas.drawRoundRect(
            RectF(thumb.left - 3f * d, thumb.top, thumb.right + 3f * d, thumb.bottom),
            radius, radius, thumbGlow
        )
        thumbPaint.shader = LinearGradient(
            0f, thumb.top, 0f, thumb.bottom,
            intArrayOf(0xFF2BFF5B.toInt(), 0xFF0F7A26.toInt(),
                       0xFF062F10.toInt(), 0xFF1FBF44.toInt()),
            floatArrayOf(0f, 0.48f, 0.52f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawRoundRect(thumb, radius, radius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> parent?.requestDisallowInterceptTouchEvent(true)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent?.requestDisallowInterceptTouchEvent(false)
        }
        if (track.width() <= 0f) return true
        val v = ((event.x - track.left) / track.width()).coerceIn(0f, 1f)
        if (v != value) {
            value = v
            invalidate()
            onValueChanged?.invoke(v)
        }
        if (event.actionMasked == MotionEvent.ACTION_DOWN) performClick()
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
