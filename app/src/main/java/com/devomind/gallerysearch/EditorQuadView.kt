package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/** Draggable 4-corner quad for perspective correction (order: TL, TR, BR, BL). */
class EditorQuadView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val bounds = RectF()
    private val pts = Array(4) { PointF() }
    private var ready = false
    private var dragging = -1

    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT; style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x22FFFFFF }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.FILL }
    private val knobRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }

    private val touchSlop = dp(28f)
    private val knobRadius = dp(8f)

    /** Source bitmap for the magnifier loupe shown while dragging a corner. */
    var magnifierSource: Bitmap? = null

    fun setImageBounds(r: RectF) {
        bounds.set(r)
        val ix = r.width() * 0.12f
        val iy = r.height() * 0.12f
        pts[0].set(r.left + ix, r.top + iy)
        pts[1].set(r.right - ix, r.top + iy)
        pts[2].set(r.right - ix, r.bottom - iy)
        pts[3].set(r.left + ix, r.bottom - iy)
        ready = true
        invalidate()
    }

    /** Corners as 0..1 fractions of the image bounds, order TL,TR,BR,BL. */
    fun normalizedCorners(): FloatArray? {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return null
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = ((pts[i].x - bounds.left) / bounds.width()).coerceIn(0f, 1f)
            out[i * 2 + 1] = ((pts[i].y - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        }
        return out
    }

    override fun onDraw(canvas: Canvas) {
        if (!ready) return
        val path = Path().apply {
            moveTo(pts[0].x, pts[0].y)
            lineTo(pts[1].x, pts[1].y)
            lineTo(pts[2].x, pts[2].y)
            lineTo(pts[3].x, pts[3].y)
            close()
        }
        canvas.drawPath(path, fill)
        canvas.drawPath(path, edge)
        for (p in pts) {
            canvas.drawCircle(p.x, p.y, knobRadius, knob)
            canvas.drawCircle(p.x, p.y, knobRadius, knobRing)
        }

        val src = magnifierSource
        if (src != null && dragging in 0..3) {
            val p = pts[dragging]
            EditorMagnifier.draw(canvas, src, bounds, p.x, p.y, resources.displayMetrics.density)
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ready) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = nearestCorner(event.x, event.y)
                return dragging >= 0
            }
            MotionEvent.ACTION_MOVE -> {
                if (dragging >= 0) {
                    pts[dragging].set(
                        event.x.coerceIn(bounds.left, bounds.right),
                        event.y.coerceIn(bounds.top, bounds.bottom)
                    )
                    invalidate()
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = -1; return true }
        }
        return false
    }

    private fun nearestCorner(x: Float, y: Float): Int {
        var best = -1
        var bestD = touchSlop
        for (i in 0 until 4) {
            val d = hypot((x - pts[i].x).toDouble(), (y - pts[i].y).toDouble()).toFloat()
            if (d <= bestD) { bestD = d; best = i }
        }
        return best
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private companion object { const val ACCENT = 0xFF3B9EFF.toInt() }
}
