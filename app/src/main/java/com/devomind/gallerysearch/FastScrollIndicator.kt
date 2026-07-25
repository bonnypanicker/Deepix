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
    private val accentColor = DesignTokens.accent(context)

    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xBB8A8A8A.toInt()
        style = Paint.Style.FILL
    }
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x00000000.toInt()
    }
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
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
            // Track the thumb on every scroll (drag, fling, programmatic) so it
            // never jumps or lags behind the content.
            if (!isDragging) {
                updateFromOffset(rv.computeVerticalScrollOffset())
            }
        }
    }

    fun syncToRecyclerView() {
        val rv = recyclerView ?: return
        updateFromOffset(rv.computeVerticalScrollOffset())
        invalidate()
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

    // The track is confined to the listing area: it starts below the search-bar
    // header and ends above the bottom nav, both of which the host applies as the
    // RecyclerView's top/bottom padding. The inset clears the largest thumb radius
    // (the enlarged drag dot) so the dot never overlaps the search bar or bottom nav.
    private fun trackTop(): Float = (recyclerView?.paddingTop?.toFloat() ?: 0f) + dip(14f)

    private fun trackBottom(): Float = height - (recyclerView?.paddingBottom?.toFloat() ?: 0f) - dip(14f)

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        if (isDragging) {
            val cy = thumbY.coerceIn(trackTop(), trackBottom())
            thumbRadius = dip(12f)
            thumbPaint.color = accentColor

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
                canvas.drawRoundRect(bubbleRect, dip(2f), dip(2f), bubblePaint)
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
            val cy = thumbY.coerceIn(trackTop(), trackBottom())
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
                if (!isTouchOnThumb(event.x, event.y)) return false
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

    /**
     * Only a drag that starts on the thumb dot itself begins fast-scrolling; touches elsewhere on
     * the strip fall through to the list. The hit zone is far larger than the visual dot (the dot
     * stays small) so it's easy to grab: a wide horizontal band and a tall vertical window centred
     * on the dot.
     */
    private fun isTouchOnThumb(x: Float, y: Float): Boolean {
        if (x < width - dip(56f)) return false
        val cy = thumbY.coerceIn(trackTop(), trackBottom())
        return Math.abs(y - cy) <= dip(28f)
    }

    private fun scrollTo(touchY: Float, rv: RecyclerView) {
        val lm = rv.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val itemCount = rv.adapter?.itemCount ?: 0
        if (itemCount == 0) return

        val top = trackTop()
        val usable = (trackBottom() - top).coerceAtLeast(1f)
        val fraction = ((touchY - top) / usable).coerceIn(0f, 1f)

        // Position-based jump: reliable and smooth for variable-height grids,
        // unlike scrollBy against an estimated pixel range.
        val target = (fraction * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
        lm.scrollToPositionWithOffset(target, 0)

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
        val top = trackTop()
        val cy = top + fraction.coerceIn(0f, 1f) * (trackBottom() - top).coerceAtLeast(1f)
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
        // Headerless listings (name/size sort) have no label to show, so drop the bubble
        // rather than leaving a stale month from the previous order.
        bubbleText = findNearestHeader(cells, pos)?.title.orEmpty()
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
