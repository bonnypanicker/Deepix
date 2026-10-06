package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Two-shape accent orb (ring + disc) breathing in inverse phase while indexing runs, drawn
 * statically otherwise. Geometry follows the reference artifact (ring r88/stroke10, disc r72 of
 * a 200 viewBox) scaled so the ring peak stays inside the view — no square clip at the widest
 * point. The breath is a cosine wave, so velocity is continuous across the whole loop: it never
 * dwells at rest or peak, and 0 and 1 of the phase close seamlessly.
 *
 * The phase is read from `SystemClock.uptimeMillis()` and frames are requested through
 * [View.postOnAnimation] rather than driven by an Animator, on purpose: an Animator inherits the
 * system "Animator duration scale", and a device with that at 0 — a battery-saver and accessibility
 * setting, and the default state of some ROMs — collapses every frame to the end value. The orb then
 * sits at its resting shape forever, and no amount of lifecycle re-wiring in this class can fix it.
 * A clock plus a frame callback is not a presentation animation, so it breathes on such a device too.
 *
 * Scale alone moves this view's edge by a couple of pixels at 22 dp, which is below what reads as
 * motion, so the ring also dims as it expands and the disc brightens as it contracts.
 */
class IndexingOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val accentColor = context.obtainStyledAttributes(intArrayOf(R.attr.accentColor)).run {
        try {
            getColor(0, 0xff1170ee.toInt())
        } finally {
            recycle()
        }
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }

    private var indexing = false
    private var frameRequested = false

    /** Re-posts itself while the orb should breathe, and stops by itself if that ends mid-frame. */
    private val breatheFrame = object : Runnable {
        override fun run() {
            frameRequested = false
            if (!shouldBreathe()) return
            invalidate()
            requestNextFrame()
        }
    }

    fun setIndexing(active: Boolean) {
        indexing = active
        updateAnimation()
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val contentW = (width - paddingLeft - paddingRight).toFloat()
        val contentH = (height - paddingTop - paddingBottom).toFloat()
        val size = min(contentW, contentH)
        if (size <= 0f) return
        val centerX = paddingLeft + contentW / 2f
        val centerY = paddingTop + contentH / 2f

        ringPaint.strokeWidth = size * RING_STROKE
        val ringRadius = size * RING_RADIUS
        val discRadius = size * DISC_RADIUS

        if (!indexing) {
            ringPaint.alpha = FULL_ALPHA
            discPaint.alpha = FULL_ALPHA
            canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)
            canvas.drawCircle(centerX, centerY, discRadius, discPaint)
            return
        }

        val wave = waveAt(phaseAt(SystemClock.uptimeMillis()))

        canvas.save()
        canvas.scale(scaleAt(wave, RING_PEAK_SCALE), scaleAt(wave, RING_PEAK_SCALE), centerX, centerY)
        ringPaint.alpha = ringAlphaAt(wave)
        canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)
        canvas.restore()

        canvas.save()
        canvas.scale(scaleAt(wave, DISC_MIN_SCALE), scaleAt(wave, DISC_MIN_SCALE), centerX, centerY)
        discPaint.alpha = discAlphaAt(wave)
        canvas.drawCircle(centerX, centerY, discRadius, discPaint)
        canvas.restore()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        updateAnimation()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        stopBreathing()
        super.onDetachedFromWindow()
    }

    private fun shouldBreathe() =
        indexing && isAttachedToWindow && isShown && windowVisibility == VISIBLE

    private fun updateAnimation() {
        if (shouldBreathe()) {
            requestNextFrame()
        } else {
            stopBreathing()
        }
    }

    private fun requestNextFrame() {
        if (frameRequested || !shouldBreathe()) return
        frameRequested = true
        postOnAnimation(breatheFrame)
    }

    private fun stopBreathing() {
        if (frameRequested) {
            removeCallbacks(breatheFrame)
            frameRequested = false
        }
    }

    companion object {
        const val LOOP_DURATION_MS = 2_600L
        const val FULL_ALPHA = 255

        // Reference proportions (ring 0.44/stroke 0.05/disc 0.36 of the viewBox) scaled by
        // 1/1.10 so the ring's gentle peak stays fractionally inside the view bounds.
        const val RING_RADIUS = 0.40f
        const val RING_STROKE = 0.045f
        const val DISC_RADIUS = 0.327f
        const val RING_PEAK_SCALE = 1.12f
        const val DISC_MIN_SCALE = 0.88f

        // The dim floor each shape reaches at the far end of its breath. Kept well below full so the
        // swing is legible at the 22 dp the search bar gives it.
        const val RING_DIM_ALPHA = 110
        const val DISC_DIM_ALPHA = 165

        /** Loop position in `0f..1f` for a monotonic clock reading. */
        fun phaseAt(uptimeMillis: Long): Float =
            (uptimeMillis.mod(LOOP_DURATION_MS)).toFloat() / LOOP_DURATION_MS

        /**
         * One breath: 0 at both ends of the loop, 1 in the middle, smooth everywhere — a cosine, so
         * the seam at 0/1 has no jump and no plateau at either extreme.
         */
        fun waveAt(phase: Float): Float =
            ((1.0 - Math.cos(2.0 * Math.PI * phase.toDouble())) / 2.0).toFloat()

        /** 1 at rest, [target] at the peak of the breath. */
        fun scaleAt(wave: Float, target: Float): Float = 1f + (target - 1f) * wave

        /** The ring fades outward as it grows; the disc brightens inward as it shrinks. */
        fun ringAlphaAt(wave: Float): Int =
            (FULL_ALPHA - (FULL_ALPHA - RING_DIM_ALPHA) * wave).toInt()

        fun discAlphaAt(wave: Float): Int =
            (DISC_DIM_ALPHA + (FULL_ALPHA - DISC_DIM_ALPHA) * wave).toInt()
    }
}
