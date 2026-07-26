package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Google-Photos-style trim timeline: a filmstrip of frame thumbnails with draggable
 * start/end handles and a playback position indicator. Times are reported as
 * fractions (0..1) of the full duration; the activity maps them to milliseconds.
 */
class VideoTrimView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val accent = DesignTokens.accent(context)

    /** Fired continuously while a handle is dragged. */
    var onRangeChanged: ((startFraction: Float, endFraction: Float) -> Unit)? = null

    /** Fired when the user releases a handle (good moment to seek the preview). */
    var onRangeCommitted: ((startFraction: Float, endFraction: Float) -> Unit)? = null

    var startFraction = 0f
        private set
    var endFraction = 1f
        private set

    /** Playback position (0..1) drawn as a thin white line inside the kept range. */
    var playheadFraction = 0f
        set(value) {
            field = value.coerceIn(0f, 1f)
            invalidate()
        }

    private var frames: List<Bitmap> = emptyList()

    private val framePaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accent
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accent }
    private val handleGripPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF000000.toInt()
        strokeWidth = dp(2f)
        strokeCap = Paint.Cap.ROUND
    }
    private val playheadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        strokeWidth = dp(2f)
    }

    private val handleWidth = dp(14f)
    private val stripInset = dp(14f)
    private val touchSlop = dp(24f)
    private val minRangePx = dp(36f)

    private enum class Drag { NONE, START, END }
    private var drag = Drag.NONE

    fun setFrames(bitmaps: List<Bitmap>) {
        frames.filterNot { it in bitmaps }.forEach { if (!it.isRecycled) it.recycle() }
        frames = bitmaps
        invalidate()
    }

    fun releaseFrames() {
        frames.forEach { if (!it.isRecycled) it.recycle() }
        frames = emptyList()
    }

    fun setRange(start: Float, end: Float) {
        startFraction = start.coerceIn(0f, 1f)
        endFraction = end.coerceIn(startFraction, 1f)
        invalidate()
    }

    private fun stripRect() = RectF(stripInset, dp(6f), width - stripInset, height - dp(6f))

    override fun onDraw(canvas: Canvas) {
        val strip = stripRect()
        if (strip.width() <= 0f) return

        // Filmstrip frames.
        if (frames.isNotEmpty()) {
            val frameWidth = strip.width() / frames.size
            frames.forEachIndexed { index, frame ->
                val left = strip.left + index * frameWidth
                val dst = RectF(left, strip.top, left + frameWidth, strip.bottom)
                canvas.drawBitmap(frame, null, dst, framePaint)
            }
        } else {
            canvas.drawRect(strip, Paint().apply { color = 0xFF1A1A1A.toInt() })
        }

        val startX = strip.left + startFraction * strip.width()
        val endX = strip.left + endFraction * strip.width()

        // Dim the trimmed-away ends.
        if (startX > strip.left) canvas.drawRect(strip.left, strip.top, startX, strip.bottom, dimPaint)
        if (endX < strip.right) canvas.drawRect(endX, strip.top, strip.right, strip.bottom, dimPaint)

        // Selection border.
        canvas.drawRect(startX, strip.top, endX, strip.bottom, borderPaint)

        // Handles: accent tabs with a grip line, mirroring the Metro accent language.
        drawHandle(canvas, startX, strip, leading = true)
        drawHandle(canvas, endX, strip, leading = false)

        // Playhead inside the kept range.
        val playX = strip.left + playheadFraction * strip.width()
        if (playX in startX..endX) {
            canvas.drawLine(playX, strip.top - dp(3f), playX, strip.bottom + dp(3f), playheadPaint)
        }
    }

    private fun drawHandle(canvas: Canvas, x: Float, strip: RectF, leading: Boolean) {
        val rect = if (leading) {
            RectF(x - handleWidth, strip.top - dp(3f), x, strip.bottom + dp(3f))
        } else {
            RectF(x, strip.top - dp(3f), x + handleWidth, strip.bottom + dp(3f))
        }
        canvas.drawRoundRect(rect, dp(3f), dp(3f), handlePaint)
        val gripX = rect.centerX()
        canvas.drawLine(gripX, rect.centerY() - dp(7f), gripX, rect.centerY() + dp(7f), handleGripPaint)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val strip = stripRect()
        if (strip.width() <= 0f) return false
        val startX = strip.left + startFraction * strip.width()
        val endX = strip.left + endFraction * strip.width()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = when {
                    abs(event.x - startX) <= touchSlop && abs(event.x - startX) <= abs(event.x - endX) -> Drag.START
                    abs(event.x - endX) <= touchSlop -> Drag.END
                    else -> Drag.NONE
                }
                if (drag != Drag.NONE) parent?.requestDisallowInterceptTouchEvent(true)
                return drag != Drag.NONE
            }
            MotionEvent.ACTION_MOVE -> {
                if (drag == Drag.NONE) return false
                val fraction = ((event.x - strip.left) / strip.width()).coerceIn(0f, 1f)
                val minRange = minRangePx / strip.width()
                if (drag == Drag.START) {
                    startFraction = fraction.coerceAtMost(endFraction - minRange).coerceAtLeast(0f)
                } else {
                    endFraction = fraction.coerceAtLeast(startFraction + minRange).coerceAtMost(1f)
                }
                onRangeChanged?.invoke(startFraction, endFraction)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (drag != Drag.NONE) {
                    drag = Drag.NONE
                    onRangeCommitted?.invoke(startFraction, endFraction)
                    return true
                }
            }
        }
        return false
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        releaseFrames()
    }
}
