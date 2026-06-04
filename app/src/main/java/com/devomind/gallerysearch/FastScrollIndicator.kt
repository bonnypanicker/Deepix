package com.devomind.gallerysearch

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class FastScrollIndicator @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xBB8A8A8A.toInt()
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x00000000.toInt()
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xEE5B4FE8.toInt()
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = dip(13f)
        textAlign = Paint.Align.CENTER
        isFakeBoldText = false
    }

    private var thumbY = 0f
    private var thumbRadius = dip(5f)
    private val bubbleRect = RectF()
    private var bubbleText = ""
    private var showBubble = false

    private var fadeAnim: ObjectAnimator? = null
    private var isDragging = false
    private var recyclerView: RecyclerView? = null
    private var adapter: ImageAdapter? = null

    private val scrollListener = object : RecyclerView.OnScrollListener() {
        override fun onScrollStateChanged(rv: RecyclerView, newState: Int) {
            when (newState) {
                RecyclerView.SCROLL_STATE_DRAGGING -> {
                    show()
                    updateFromOffset(rv.computeVerticalScrollOffset())
                }
                RecyclerView.SCROLL_STATE_IDLE -> {
                    if (!isDragging) hide()
                }
            }
        }

        override fun onScrolled(rv: RecyclerView, dx: Int, dy: Int) {
            if (!isDragging && rv.scrollState == RecyclerView.SCROLL_STATE_DRAGGING) {
                updateFromOffset(rv.computeVerticalScrollOffset())
            }
        }
    }

    fun attach(rv: RecyclerView, imageAdapter: ImageAdapter) {
        this.recyclerView = rv
        this.adapter = imageAdapter
        rv.addOnScrollListener(scrollListener)
        alpha = 0f
        visibility = View.VISIBLE
    }

    fun detach() {
        recyclerView?.removeOnScrollListener(scrollListener)
        recyclerView = null
        adapter = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        updateFromOffset(recyclerView?.computeVerticalScrollOffset() ?: 0)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isDragging) {
            val cy = thumbY.coerceIn(dip(20f), height - dip(20f))
            thumbRadius = dip(12f)
            thumbPaint.color = 0xEE5B4FE8.toInt()

            if (showBubble && bubbleText.isNotEmpty()) {
                val bw = textPaint.measureText(bubbleText) + dip(32f)
                val bh = dip(36f)
                val bx = width - dip(48f) - bw / 2f
                val by = cy - bh - dip(12f)
                bubbleRect.set(
                    Math.max(dip(8f), bx),
                    by,
                    Math.min(width - dip(8f), bx + bw),
                    by + bh
                )
                canvas.drawRoundRect(bubbleRect, dip(18f), dip(18f), bubblePaint)
                canvas.drawText(
                    bubbleText,
                    bubbleRect.centerX(),
                    bubbleRect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2f,
                    textPaint
                )
            }

            canvas.drawCircle(width - dip(24f), cy, thumbRadius, thumbPaint)
        } else {
            thumbPaint.color = 0xBB8A8A8A.toInt()
            thumbRadius = dip(5f)
            val cy = thumbY.coerceIn(dip(6f), height - dip(6f))
            if (alpha > 0.1f) {
                canvas.drawCircle(width - dip(24f), cy, thumbRadius, thumbPaint)
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        val rv = recyclerView ?: return false

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                if (!isTouchInScrollArea(event.x, event.y)) return false
                isDragging = true
                fadeAnim?.cancel()
                alpha = 1f
                showBubble = true
                scrollTo(event.y, rv)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                scrollTo(event.y, rv)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isDragging = false
                showBubble = false
                hide()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun isTouchInScrollArea(x: Float, y: Float): Boolean {
        return x > width - dip(48f)
    }

    private fun scrollTo(touchY: Float, rv: RecyclerView) {
        val totalRange = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        if (totalRange <= extent) return

        val fraction = (touchY / height).coerceIn(0f, 1f)
        val targetOffset = (fraction * (totalRange - extent)).toInt()
        rv.scrollBy(0, targetOffset - rv.computeVerticalScrollOffset())

        thumbY = touchY
        updateFromFraction(fraction)
        invalidate()
    }

    private fun updateFromOffset(offset: Int) {
        val rv = recyclerView ?: return
        val totalRange = rv.computeVerticalScrollRange()
        val extent = rv.computeVerticalScrollExtent()
        if (totalRange <= extent) {
            thumbY = 0f
            invalidate()
            return
        }
        val fraction = offset.toFloat() / (totalRange - extent)
        val cy = fraction * height
        if (Math.abs(thumbY - cy) > dip(1f)) {
            thumbY = cy
            updateFromFraction(fraction)
            invalidate()
        }
    }

    private fun updateFromFraction(fraction: Float) {
        val cells = adapter?.cells ?: return
        val total = cells.size
        if (total == 0) return
        val pos = (fraction * total).toInt().coerceIn(0, total - 1)
        val header = findNearestHeader(cells, pos)
        if (header != null) {
            bubbleText = header.title
        }
    }

    private fun findNearestHeader(cells: List<GalleryCell>, pos: Int): GalleryCell.Header? {
        for (i in pos downTo 0) {
            val cell = cells.getOrNull(i)
            if (cell is GalleryCell.Header) return cell
        }
        for (i in pos until cells.size) {
            val cell = cells.getOrNull(i)
            if (cell is GalleryCell.Header) return cell
        }
        return null
    }

    private fun show() {
        fadeAnim?.cancel()
        fadeAnim = ObjectAnimator.ofFloat(this, "alpha", 1f).apply {
            duration = 150
            start()
        }
    }

    private fun hide() {
        fadeAnim?.cancel()
        fadeAnim = ObjectAnimator.ofFloat(this, "alpha", 0f).apply {
            startDelay = 800
            duration = 200
            start()
        }
    }

    private fun dip(dp: Float): Float = dp * resources.displayMetrics.density
}
