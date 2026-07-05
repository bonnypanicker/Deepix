package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/** Crop rectangle overlay with corner handles, move, optional aspect-ratio lock and thirds grid. */
class EditorCropView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val bounds = RectF()
    private val sel = RectF()
    private var ready = false
    private var aspect: Float? = null  // width / height, null = free

    private val scrim = Paint().apply { color = 0x99000000.toInt() }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT; style = Paint.Style.STROKE; strokeWidth = dp(2f)
    }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF; style = Paint.Style.STROKE; strokeWidth = dp(1f)
    }
    private val handle = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT; style = Paint.Style.STROKE; strokeWidth = dp(3f); strokeCap = Paint.Cap.ROUND
    }

    private val touchSlop = dp(26f)
    private val minSize = dp(48f)
    private val handleLen = dp(18f)

    private enum class H { NONE, MOVE, TL, TR, BL, BR }
    private var active = H.NONE
    private var lastX = 0f
    private var lastY = 0f

    fun setImageBounds(r: RectF) {
        bounds.set(r)
        sel.set(r)
        ready = true
        aspect?.let { applyAspect(it) }
        invalidate()
    }

    fun setAspect(ratio: Float?) {
        aspect = ratio
        if (ready && ratio != null) applyAspect(ratio) else if (ready) sel.set(bounds)
        invalidate()
    }

    /** Selection as 0..1 fractions of the image bounds. */
    fun normalizedSelection(): RectF? {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return null
        return RectF(
            ((sel.left - bounds.left) / bounds.width()).coerceIn(0f, 1f),
            ((sel.top - bounds.top) / bounds.height()).coerceIn(0f, 1f),
            ((sel.right - bounds.left) / bounds.width()).coerceIn(0f, 1f),
            ((sel.bottom - bounds.top) / bounds.height()).coerceIn(0f, 1f)
        )
    }

    private fun applyAspect(ratio: Float) {
        val cx = bounds.centerX()
        val cy = bounds.centerY()
        var w = bounds.width()
        var h = w / ratio
        if (h > bounds.height()) {
            h = bounds.height()
            w = h * ratio
        }
        sel.set(cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2)
    }

    override fun onDraw(canvas: Canvas) {
        if (!ready) return
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, sel.top, scrim)
        canvas.drawRect(0f, sel.bottom, w, h, scrim)
        canvas.drawRect(0f, sel.top, sel.left, sel.bottom, scrim)
        canvas.drawRect(sel.right, sel.top, w, sel.bottom, scrim)

        val tw = sel.width() / 3f; val th = sel.height() / 3f
        canvas.drawLine(sel.left + tw, sel.top, sel.left + tw, sel.bottom, grid)
        canvas.drawLine(sel.left + 2 * tw, sel.top, sel.left + 2 * tw, sel.bottom, grid)
        canvas.drawLine(sel.left, sel.top + th, sel.right, sel.top + th, grid)
        canvas.drawLine(sel.left, sel.top + 2 * th, sel.right, sel.top + 2 * th, grid)

        canvas.drawRect(sel, border)
        corner(canvas, sel.left, sel.top, 1, 1)
        corner(canvas, sel.right, sel.top, -1, 1)
        corner(canvas, sel.left, sel.bottom, 1, -1)
        corner(canvas, sel.right, sel.bottom, -1, -1)
    }

    private fun corner(c: Canvas, x: Float, y: Float, sx: Int, sy: Int) {
        c.drawLine(x, y, x + sx * handleLen, y, handle)
        c.drawLine(x, y, x, y + sy * handleLen, handle)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!ready) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { active = detect(event.x, event.y); lastX = event.x; lastY = event.y; return true }
            MotionEvent.ACTION_MOVE -> { drag(event.x, event.y); lastX = event.x; lastY = event.y; invalidate(); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { active = H.NONE; return true }
        }
        return false
    }

    private fun detect(x: Float, y: Float): H {
        if (near(x, y, sel.left, sel.top)) return H.TL
        if (near(x, y, sel.right, sel.top)) return H.TR
        if (near(x, y, sel.left, sel.bottom)) return H.BL
        if (near(x, y, sel.right, sel.bottom)) return H.BR
        if (sel.contains(x, y)) return H.MOVE
        return H.NONE
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float) =
        abs(x - cx) <= touchSlop && abs(y - cy) <= touchSlop

    private fun drag(x: Float, y: Float) {
        val cx = x.coerceIn(bounds.left, bounds.right)
        val cy = y.coerceIn(bounds.top, bounds.bottom)
        when (active) {
            H.MOVE -> {
                val dx = (x - lastX).coerceIn(bounds.left - sel.left, bounds.right - sel.right)
                val dy = (y - lastY).coerceIn(bounds.top - sel.top, bounds.bottom - sel.bottom)
                sel.offset(dx, dy)
            }
            H.TL -> resize(cx, cy, right = false, bottom = false)
            H.TR -> resize(cx, cy, right = true, bottom = false)
            H.BL -> resize(cx, cy, right = false, bottom = true)
            H.BR -> resize(cx, cy, right = true, bottom = true)
            H.NONE -> Unit
        }
    }

    private fun resize(x: Float, y: Float, right: Boolean, bottom: Boolean) {
        if (right) sel.right = x.coerceAtLeast(sel.left + minSize) else sel.left = x.coerceAtMost(sel.right - minSize)
        if (bottom) sel.bottom = y.coerceAtLeast(sel.top + minSize) else sel.top = y.coerceAtMost(sel.bottom - minSize)
        aspect?.let { ratio ->
            // Keep ratio by deriving height from the new width, anchored on the dragged corner.
            val w = sel.width()
            val h = w / ratio
            if (bottom) sel.bottom = (sel.top + h) else sel.top = (sel.bottom - h)
            // Clamp inside bounds without breaking the ratio.
            if (sel.top < bounds.top) { sel.top = bounds.top; sel.right = sel.left + (sel.height() * ratio) }
            if (sel.bottom > bounds.bottom) { sel.bottom = bounds.bottom; sel.right = sel.left + (sel.height() * ratio) }
        }
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    private companion object { const val ACCENT = 0xFF3B9EFF.toInt() }
}
