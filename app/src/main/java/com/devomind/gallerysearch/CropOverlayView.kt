package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Interactive crop rectangle used by the viewer's "search a region" flow.
 *
 * The user drags corner-to-corner to draw a rectangle, drags the corner handles to resize, or
 * drags inside the rectangle to move it. The selection is always clamped to [imageBounds] (the
 * on-screen rect of the displayed photo) and kept above a minimum size.
 *
 * Coordinates are the view's own pixels; [normalizedSelection] converts to 0..1 within the image
 * bounds so the caller can map the selection onto the source bitmap regardless of scaling.
 */
class CropOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val imageBounds = RectF()
    private val selection = RectF()
    private var hasSelection = false

    private val scrimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.STROKE
        strokeWidth = dp(2f)
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55FFFFFF
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT
        style = Paint.Style.STROKE
        strokeWidth = dp(3f)
        strokeCap = Paint.Cap.ROUND
    }

    private val touchSlop = dp(28f)
    private val minSize = dp(56f)
    private val handleLen = dp(20f)

    private enum class Handle { NONE, MOVE, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, DRAW }

    private var activeHandle = Handle.NONE
    private var lastX = 0f
    private var lastY = 0f
    private var drawAnchorX = 0f
    private var drawAnchorY = 0f

    /** Sets the on-screen photo rect and initialises the selection to a centred inset. */
    fun setImageBounds(rect: RectF) {
        imageBounds.set(rect)
        val insetW = rect.width() * (1f - DEFAULT_COVERAGE) / 2f
        val insetH = rect.height() * (1f - DEFAULT_COVERAGE) / 2f
        selection.set(
            rect.left + insetW,
            rect.top + insetH,
            rect.right - insetW,
            rect.bottom - insetH
        )
        hasSelection = true
        invalidate()
    }

    /** Current selection as 0..1 fractions of the image bounds, or null if bounds are unset. */
    fun normalizedSelection(): RectF? {
        if (imageBounds.width() <= 0f || imageBounds.height() <= 0f) return null
        val l = ((selection.left - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f)
        val t = ((selection.top - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f)
        val r = ((selection.right - imageBounds.left) / imageBounds.width()).coerceIn(0f, 1f)
        val b = ((selection.bottom - imageBounds.top) / imageBounds.height()).coerceIn(0f, 1f)
        return RectF(minOf(l, r), minOf(t, b), maxOf(l, r), maxOf(t, b))
    }

    override fun onDraw(canvas: Canvas) {
        if (!hasSelection) return
        val w = width.toFloat()
        val h = height.toFloat()

        // Dim everything outside the selection.
        canvas.drawRect(0f, 0f, w, selection.top, scrimPaint)
        canvas.drawRect(0f, selection.bottom, w, h, scrimPaint)
        canvas.drawRect(0f, selection.top, selection.left, selection.bottom, scrimPaint)
        canvas.drawRect(selection.right, selection.top, w, selection.bottom, scrimPaint)

        // Rule-of-thirds guides.
        val thirdW = selection.width() / 3f
        val thirdH = selection.height() / 3f
        canvas.drawLine(selection.left + thirdW, selection.top, selection.left + thirdW, selection.bottom, gridPaint)
        canvas.drawLine(selection.left + 2 * thirdW, selection.top, selection.left + 2 * thirdW, selection.bottom, gridPaint)
        canvas.drawLine(selection.left, selection.top + thirdH, selection.right, selection.top + thirdH, gridPaint)
        canvas.drawLine(selection.left, selection.top + 2 * thirdH, selection.right, selection.top + 2 * thirdH, gridPaint)

        // Border + corner brackets.
        canvas.drawRect(selection, borderPaint)
        drawCorner(canvas, selection.left, selection.top, 1, 1)
        drawCorner(canvas, selection.right, selection.top, -1, 1)
        drawCorner(canvas, selection.left, selection.bottom, 1, -1)
        drawCorner(canvas, selection.right, selection.bottom, -1, -1)
    }

    private fun drawCorner(canvas: Canvas, x: Float, y: Float, signX: Int, signY: Int) {
        canvas.drawLine(x, y, x + signX * handleLen, y, handlePaint)
        canvas.drawLine(x, y, x, y + signY * handleLen, handlePaint)
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!hasSelection) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                activeHandle = detectHandle(event.x, event.y)
                lastX = event.x
                lastY = event.y
                if (activeHandle == Handle.DRAW) {
                    drawAnchorX = event.x.coerceIn(imageBounds.left, imageBounds.right)
                    drawAnchorY = event.y.coerceIn(imageBounds.top, imageBounds.bottom)
                    selection.set(drawAnchorX, drawAnchorY, drawAnchorX, drawAnchorY)
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                applyDrag(event.x, event.y)
                lastX = event.x
                lastY = event.y
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                normalizeAndClamp()
                activeHandle = Handle.NONE
                invalidate()
                return true
            }
        }
        return false
    }

    private fun detectHandle(x: Float, y: Float): Handle {
        if (near(x, y, selection.left, selection.top)) return Handle.TOP_LEFT
        if (near(x, y, selection.right, selection.top)) return Handle.TOP_RIGHT
        if (near(x, y, selection.left, selection.bottom)) return Handle.BOTTOM_LEFT
        if (near(x, y, selection.right, selection.bottom)) return Handle.BOTTOM_RIGHT
        if (selection.contains(x, y)) return Handle.MOVE
        return Handle.DRAW
    }

    private fun near(x: Float, y: Float, cx: Float, cy: Float): Boolean =
        abs(x - cx) <= touchSlop && abs(y - cy) <= touchSlop

    private fun applyDrag(x: Float, y: Float) {
        val clampedX = x.coerceIn(imageBounds.left, imageBounds.right)
        val clampedY = y.coerceIn(imageBounds.top, imageBounds.bottom)
        when (activeHandle) {
            Handle.MOVE -> {
                var dx = x - lastX
                var dy = y - lastY
                dx = dx.coerceIn(imageBounds.left - selection.left, imageBounds.right - selection.right)
                dy = dy.coerceIn(imageBounds.top - selection.top, imageBounds.bottom - selection.bottom)
                selection.offset(dx, dy)
            }
            Handle.TOP_LEFT -> {
                selection.left = clampedX.coerceAtMost(selection.right - minSize)
                selection.top = clampedY.coerceAtMost(selection.bottom - minSize)
            }
            Handle.TOP_RIGHT -> {
                selection.right = clampedX.coerceAtLeast(selection.left + minSize)
                selection.top = clampedY.coerceAtMost(selection.bottom - minSize)
            }
            Handle.BOTTOM_LEFT -> {
                selection.left = clampedX.coerceAtMost(selection.right - minSize)
                selection.bottom = clampedY.coerceAtLeast(selection.top + minSize)
            }
            Handle.BOTTOM_RIGHT -> {
                selection.right = clampedX.coerceAtLeast(selection.left + minSize)
                selection.bottom = clampedY.coerceAtLeast(selection.top + minSize)
            }
            Handle.DRAW -> {
                selection.set(
                    minOf(drawAnchorX, clampedX),
                    minOf(drawAnchorY, clampedY),
                    maxOf(drawAnchorX, clampedX),
                    maxOf(drawAnchorY, clampedY)
                )
            }
            Handle.NONE -> Unit
        }
    }

    /** Ensures the rect is well-formed, at least the minimum size, and inside the image bounds. */
    private fun normalizeAndClamp() {
        if (selection.right < selection.left) {
            val tmp = selection.left; selection.left = selection.right; selection.right = tmp
        }
        if (selection.bottom < selection.top) {
            val tmp = selection.top; selection.top = selection.bottom; selection.bottom = tmp
        }
        if (selection.width() < minSize) selection.right = selection.left + minSize
        if (selection.height() < minSize) selection.bottom = selection.top + minSize

        // Shift back inside bounds if the min-size expansion pushed past an edge.
        if (selection.right > imageBounds.right) selection.offset(imageBounds.right - selection.right, 0f)
        if (selection.bottom > imageBounds.bottom) selection.offset(0f, imageBounds.bottom - selection.bottom)
        selection.left = selection.left.coerceAtLeast(imageBounds.left)
        selection.top = selection.top.coerceAtLeast(imageBounds.top)
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density

    private companion object {
        const val ACCENT = 0xFF3B9EFF.toInt()
        const val DEFAULT_COVERAGE = 0.7f
    }
}
