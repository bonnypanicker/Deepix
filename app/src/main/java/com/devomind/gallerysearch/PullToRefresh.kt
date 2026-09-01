package com.devomind.gallerysearch

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs

/**
 * Global pull-to-refresh, Metro style: there is no spinner and no circle. Pulling a library
 * grid down while it rests at the very top grows a thin accent line out of the horizontal
 * center of the top-bar border toward both edges; the line spanning the full width arms the
 * reload, and releasing an armed pull fades the full-width line in place — it never retracts
 * into a spinner. An under-threshold release snaps the line and the grid back.
 *
 * [bind] installs everything one RecyclerView needs:
 *  - an [RecyclerView.OnItemTouchListener] that claims only true downward drags at the very
 *    top (taps, horizontal scrolls, pinches and fast-scroll drags are left untouched),
 *  - a damped rubber-band translation of the grid while the finger travels,
 *  - a [RecyclerView.ItemDecoration] that paints the line + glow over everything, anchored
 *    to the top-bar border (via [borderY]) so it stays pinned there while the grid stretches.
 */
object PullToRefresh {

    fun bind(
        rv: RecyclerView,
        onRefresh: () -> Unit,
        allowPull: () -> Boolean = { true },
        onPullStart: () -> Unit = {},
        borderY: (() -> Float)? = null
    ) {
        val decoration = PullRefreshLineDecoration(
            accent = DesignTokens.accent(rv.context),
            accentArmed = DesignTokens.accentLight(rv.context),
            // Default anchor: the grid's own top edge, which is the border under the top bar
            // on the simple screens; it counter-translates the rubber-band pull.
            borderY = borderY ?: { -rv.translationY }
        )
        rv.addItemDecoration(decoration)
        rv.addOnItemTouchListener(
            PullGesture(rv, decoration, onRefresh, allowPull, onPullStart)
        )
    }
}

/**
 * The painted accent line. [progress] grows the line from the horizontal center (1.0 = full
 * width); [fade] multiplies its alpha and is driven to 0 for the completed-pull fade while
 * [progress] deliberately stays at 1 so the full-width line fades in place.
 */
private class PullRefreshLineDecoration(
    private val accent: Int,
    private val accentArmed: Int,
    private val borderY: () -> Float
) : RecyclerView.ItemDecoration() {

    var progress = 0f
    var fade = 1f
    var armed = false

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun reset() {
        progress = 0f
        fade = 1f
        armed = false
    }

    override fun onDrawOver(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
        if (progress <= 0f || fade <= 0f) return
        val d = parent.resources.displayMetrics.density
        val half = parent.width / 2f * progress
        val y = borderY()
        val color = if (armed) accentArmed else accent

        canvas.save()
        canvas.translate(parent.width / 2f, y)

        // Soft glow radiating from the line, biased downward so the band stays readable
        // below the border (the half above it sits under the opaque top bar / is clipped).
        val glowTop = -GLOW_RISE_DP * d
        val glowBottom = GLOW_FALL_DP * d
        glowPaint.shader = LinearGradient(
            0f, glowTop, 0f, glowBottom,
            intArrayOf(
                Color.TRANSPARENT,
                ColorUtils.setAlphaComponent(color, (GLOW_MAX_ALPHA * fade).toInt().coerceIn(0, 255)),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, GLOW_PEAK_POSITION, 1f),
            Shader.TileMode.CLAMP
        )
        rect.set(-half, glowTop, half, glowBottom)
        canvas.drawRect(rect, glowPaint)

        // Halo: a wider translucent sheath that softens the line's edges.
        val haloHalf = HALO_DP * d
        haloPaint.color = ColorUtils.setAlphaComponent(color, (HALO_ALPHA * fade).toInt().coerceIn(0, 255))
        rect.set(-half, -haloHalf, half, haloHalf)
        canvas.drawRoundRect(rect, haloHalf, haloHalf, haloPaint)

        // Core line.
        val lineHalf = LINE_DP * d / 2f
        linePaint.color = color
        linePaint.alpha = (255f * fade).toInt().coerceIn(0, 255)
        rect.set(-half, -lineHalf, half, lineHalf)
        canvas.drawRoundRect(rect, lineHalf, lineHalf, linePaint)

        canvas.restore()
    }

    private companion object {
        const val LINE_DP = 2.5f
        const val HALO_DP = 4.5f
        const val HALO_ALPHA = 80
        const val GLOW_RISE_DP = 7f
        const val GLOW_FALL_DP = 19f
        const val GLOW_MAX_ALPHA = 85
        const val GLOW_PEAK_POSITION = 0.27f
    }
}

private class PullGesture(
    private val rv: RecyclerView,
    private val decoration: PullRefreshLineDecoration,
    private val onRefresh: () -> Unit,
    private val allowPull: () -> Boolean,
    private val onPullStart: () -> Unit
) : RecyclerView.OnItemTouchListener {

    private val touchSlop = ViewConfiguration.get(rv.context).scaledTouchSlop
    private val triggerPx = DesignTokens.PULL_TRIGGER_DP * rv.resources.displayMetrics.density
    private val maxTravelPx = DesignTokens.PULL_MAX_TRAVEL_DP * rv.resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var armed = false
    /** True while a release animation runs; blocks a second pull from fighting the fade. */
    private var settling = false

    override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pulling && !settling && allowPull() && !rv.canScrollVertically(-1)) {
                    val dy = e.y - downY
                    val dx = e.x - downX
                    // Claim only an unambiguous downward drag; anything else keeps its
                    // normal path (scroll, horizontal pager, item click).
                    if (dy > touchSlop && dy > abs(dx)) {
                        startPull()
                        applyPull(e.y)
                        return true
                    }
                }
            }
        }
        return false
    }

    override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                // A second finger is a pinch-to-rescale starting mid-pull; bail out cleanly.
                if (e.pointerCount > 1) settle(triggered = false) else applyPull(e.y)
            }
            MotionEvent.ACTION_UP -> settle(triggered = armed)
            MotionEvent.ACTION_CANCEL -> settle(triggered = false)
        }
    }

    override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // A child claimed the stream (e.g. a swipeable row) — the pull must not fight it.
        if (disallowIntercept && pulling) settle(triggered = false)
    }

    private fun startPull() {
        pulling = true
        armed = false
        onPullStart()
        rv.parent?.requestDisallowInterceptTouchEvent(true)
    }

    private fun applyPull(y: Float) {
        val damped = (y - downY) * DesignTokens.PULL_DRAG_RATE
        val progress = (damped / triggerPx).coerceIn(0f, 1f)
        armed = progress >= 1f
        decoration.progress = progress
        decoration.armed = armed
        rv.translationY = damped.coerceIn(0f, maxTravelPx)
        rv.invalidate()
    }

    /** Release: snap the grid home; a full-width pull fades the line in place, else it retracts. */
    private fun settle(triggered: Boolean) {
        if (!pulling) return
        pulling = false
        rv.parent?.requestDisallowInterceptTouchEvent(false)
        rv.animate().translationY(0f)
            .setDuration(SNAP_MS)
            .setInterpolator(DecelerateInterpolator())
            .start()

        if (triggered) {
            settling = true
            // Keep progress at 1 — the full-width line fades in place, never retracts.
            ValueAnimator.ofFloat(1f, 0f).apply {
                startDelay = ARMED_HOLD_MS
                duration = FADE_MS
                addUpdateListener {
                    decoration.fade = it.animatedValue as Float
                    rv.invalidate()
                }
                doOnEnd {
                    decoration.reset()
                    settling = false
                    rv.invalidate()
                }
            }.start()
            onRefresh()
        } else {
            decoration.armed = false
            ValueAnimator.ofFloat(decoration.progress, 0f).apply {
                duration = RETRACT_MS
                addUpdateListener {
                    decoration.progress = it.animatedValue as Float
                    rv.invalidate()
                }
                doOnEnd {
                    decoration.reset()
                    rv.invalidate()
                }
            }.start()
        }
    }

    private companion object {
        const val SNAP_MS = 220L
        const val RETRACT_MS = 260L
        /** The full-width line is held for a beat so the "spanned" state reads before it fades. */
        const val ARMED_HOLD_MS = 200L
        const val FADE_MS = 480L
    }
}
