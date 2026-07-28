package com.devomind.gallerysearch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import android.view.animation.PathInterpolator
import androidx.core.graphics.ColorUtils
import kotlin.math.min

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
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(accentColor, (255 * TRACK_ALPHA).toInt())
        style = Paint.Style.STROKE
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = accentColor
        style = Paint.Style.STROKE
    }
    private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = accentColor }
    private val easing = PathInterpolator(0.4f, 0f, 0.2f, 1f)

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
        val size = min(width, height).toFloat()
        if (size <= 0f) return

        val centerX = width / 2f
        val centerY = height / 2f
        val strokeWidth = size * RING_STROKE
        val ringRadius = size * BASE_OUTER_RADIUS - strokeWidth / 2f
        trackPaint.strokeWidth = strokeWidth
        ringPaint.strokeWidth = strokeWidth

        canvas.drawCircle(centerX, centerY, ringRadius, trackPaint)
        if (!indexing) {
            ringPaint.alpha = 255
            canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)
            discPaint.alpha = 255
            canvas.drawCircle(centerX, centerY, size * DISC_RADIUS, discPaint)
            return
        }

        val eased = easing.getInterpolation(phase)
        ringPaint.alpha = (255 * ringAlpha(eased)).toInt().coerceIn(0, 255)
        canvas.save()
        val ringScale = lerp(0.82f, 1.32f, eased)
        canvas.scale(ringScale, ringScale, centerX, centerY)
        canvas.drawCircle(centerX, centerY, ringRadius, ringPaint)
        canvas.restore()

        val secondHalf = eased > 0.55f
        val discProgress = if (secondHalf) (eased - 0.55f) / 0.45f else eased / 0.55f
        val discScale = if (secondHalf) lerp(0.62f, 1f, discProgress) else lerp(1f, 0.62f, discProgress)
        val discAlpha = if (secondHalf) lerp(0.85f, 1f, discProgress) else lerp(1f, 0.85f, discProgress)
        discPaint.alpha = (255 * discAlpha).toInt().coerceIn(0, 255)
        canvas.drawCircle(centerX, centerY, size * DISC_RADIUS * discScale, discPaint)
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

    private fun ringAlpha(value: Float): Float = when {
        value < 0.12f -> value / 0.12f
        value < 0.55f -> lerp(1f, 0.9f, (value - 0.12f) / 0.43f)
        else -> lerp(0.9f, 0f, (value - 0.55f) / 0.45f)
    }

    private fun lerp(start: Float, end: Float, amount: Float): Float =
        start + (end - start) * amount.coerceIn(0f, 1f)

    private companion object {
        const val LOOP_DURATION_MS = 1_800L
        const val BASE_OUTER_RADIUS = 0.355f
        const val RING_STROKE = 0.095f
        const val DISC_RADIUS = 0.205f
        const val TRACK_ALPHA = 0.18f
    }
}
