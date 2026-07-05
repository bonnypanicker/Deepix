package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Freehand drawing overlay. Strokes are captured as point lists in view space and exported as
 * resolution-independent [PhotoEditOps.Stroke]s (normalized to the image bounds) so the same
 * drawing can be rendered onto the full-resolution bitmap at save time.
 */
class EditorDrawView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private class Stroke(val color: Int, val width: Float) {
        val xs = ArrayList<Float>()
        val ys = ArrayList<Float>()
        val path = Path()
    }

    private val bounds = RectF()
    private val strokes = ArrayList<Stroke>()
    private var current: Stroke? = null

    var strokeColor: Int = Color.WHITE
    var strokeWidth: Float = dp(6f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    fun setImageBounds(r: RectF) { bounds.set(r); invalidate() }

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            invalidate()
        }
    }

    fun clear() {
        strokes.clear()
        current = null
        invalidate()
    }

    fun hasStrokes(): Boolean = strokes.isNotEmpty()

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        if (!bounds.isEmpty) canvas.clipRect(bounds)
        for (s in strokes) drawStroke(canvas, s)
        current?.let { drawStroke(canvas, it) }
        canvas.restore()
    }

    private fun drawStroke(canvas: Canvas, s: Stroke) {
        paint.color = s.color
        paint.strokeWidth = s.width
        canvas.drawPath(s.path, paint)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.coerceIn(bounds.left, bounds.right)
        val y = event.y.coerceIn(bounds.top, bounds.bottom)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                current = Stroke(strokeColor, strokeWidth).also {
                    it.path.moveTo(x, y); it.xs.add(x); it.ys.add(y)
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                current?.let { it.path.lineTo(x, y); it.xs.add(x); it.ys.add(y) }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                current?.let { strokes.add(it) }
                current = null
                invalidate()
                return true
            }
        }
        return false
    }

    /** Strokes normalized to the image bounds (width fraction for size), for full-res replay. */
    fun exportStrokes(): List<PhotoEditOps.Stroke> {
        if (strokes.isEmpty() || bounds.width() <= 0f || bounds.height() <= 0f) return emptyList()
        return strokes.map { s ->
            val pts = FloatArray(s.xs.size * 2)
            for (i in s.xs.indices) {
                pts[i * 2] = ((s.xs[i] - bounds.left) / bounds.width()).coerceIn(0f, 1f)
                pts[i * 2 + 1] = ((s.ys[i] - bounds.top) / bounds.height()).coerceIn(0f, 1f)
            }
            PhotoEditOps.Stroke(pts, s.color, s.width / bounds.width())
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
}
