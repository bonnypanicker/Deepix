package com.devomind.gallerysearch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Two-shape accent orb (ring + disc) breathing in inverse phase while indexing runs, drawn
 * statically otherwise. Geometry follows the reference artifact (ring r88/stroke10, disc r72 of
 * a 200 viewBox) scaled so the 1.18x ring peak stays inside the view — no square clip at the
 * widest point. The breath is a cosine wave, so velocity is continuous across the whole loop:
 * it never dwells at rest or peak, and 0 and 1 of the phase close seamlessly.
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

    private var animator: ValueAnimator? = null
    private var indexing = false
    private var visibleToUser = false
    private var phase = 0f

    fun setIndexing(active: Boolean) {
        if (indexing == active) return
        indexing = active
        updateAnimation()
        invalidate()
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
            canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)
            canvas.drawCircle(centerX, centerY, discRadius, discPaint)
            return
        }

        canvas.save()
        val ringScale = pulseScale(phase, RING_PEAK_SCALE)
        canvas.scale(ringScale, ringScale, centerX, centerY)
        canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)
        canvas.restore()

        canvas.save()
        val discScale = pulseScale(phase, DISC_MIN_SCALE)
        canvas.scale(discScale, discScale, centerX, centerY)
        canvas.drawCircle(centerX, centerY, discRadius, discPaint)
        canvas.restore()
    }

    /** One smooth breath (1 → target → 1) on a cosine wave; no plateau at either extreme. */
    private fun pulseScale(phase: Float, target: Float): Float {
        val wave = (1.0 - Math.cos(2.0 * Math.PI * phase)) / 2.0
        return 1f + (target - 1f) * wave.toFloat()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        visibleToUser = isVisible
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        visibleToUser = false
        stopAnimation()
        super.onDetachedFromWindow()
    }

    private fun updateAnimation() {
        if (indexing && visibleToUser) startAnimation() else stopAnimation()
    }

    private fun startAnimation() {
        if (animator?.isRunning == true) return
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = LOOP_DURATION_MS
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun stopAnimation() {
        animator?.cancel()
        animator = null
        phase = 0f
        invalidate()
    }

    private companion object {
        const val LOOP_DURATION_MS = 1_600L

        // Reference proportions (ring 0.44/stroke 0.05/disc 0.36 of the viewBox) scaled by
        // 1/1.10 so the ring's 1.18x peak stays fractionally inside the view bounds.
        const val RING_RADIUS = 0.40f
        const val RING_STROKE = 0.045f
        const val DISC_RADIUS = 0.327f
        const val RING_PEAK_SCALE = 1.18f
        const val DISC_MIN_SCALE = 0.72f
    }
}
