package com.devomind.gallerysearch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * PhotoView with a two-finger twist trigger.
 *
 * The twist is intentionally not a continuous rotation control. Once the accumulated twist crosses
 * a threshold, the photo rotates by one 90-degree step, like a toolbar rotate button. The transform
 * pivots around the view center and applies an extra contain scale for 90/270-degree states so the
 * rotated fitted image remains inside the visible frame.
 */
class RotatablePhotoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : PhotoView(context, attrs, defStyle) {

    var viewportFitScale: Float = 1f
        private set

    private var visualRotation = 0f
    private var twistStartAngle = 0f
    private var twistStartSpan = 0f
    private var accumulatedTwist = 0f
    private var twistActive = false
    private var rotationTriggered = false
    private var snapAnimator: ValueAnimator? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    beginTwist(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (twistActive && !rotationTriggered && event.pointerCount >= 2) {
                    updateTwist(event)
                }
            }
            MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> endTwist()
        }
        return super.dispatchTouchEvent(event)
    }

    /** Clears any temporary rotation. Call when a new image is bound into this view. */
    fun resetRotation() {
        snapAnimator?.cancel()
        snapAnimator = null
        twistActive = false
        rotationTriggered = false
        accumulatedTwist = 0f
        visualRotation = 0f
        viewportFitScale = 1f
        pivotX = width / 2f
        pivotY = height / 2f
        rotation = 0f
        scaleX = 1f
        scaleY = 1f
    }

    private fun beginTwist(event: MotionEvent) {
        snapAnimator?.cancel()
        twistStartAngle = angleBetweenFirstTwoPointers(event) ?: return
        twistStartSpan = spanBetweenFirstTwoPointers(event) ?: return
        accumulatedTwist = 0f
        twistActive = true
        rotationTriggered = false
    }

    private fun updateTwist(event: MotionEvent) {
        val angle = angleBetweenFirstTwoPointers(event) ?: return
        val span = spanBetweenFirstTwoPointers(event) ?: return
        accumulatedTwist = shortestAngleDelta(twistStartAngle, angle)

        val pinchRatio = if (twistStartSpan > 0f) abs(span - twistStartSpan) / twistStartSpan else 0f
        if (pinchRatio > PINCH_DOMINANCE_RATIO) return
        if (abs(accumulatedTwist) < TRIGGER_DEGREES) return

        rotationTriggered = true
        parent?.requestDisallowInterceptTouchEvent(true)
        rotateOneStep(if (accumulatedTwist > 0f) 90f else -90f)
    }

    private fun endTwist() {
        twistActive = false
        rotationTriggered = false
        accumulatedTwist = 0f
    }

    private fun rotateOneStep(stepDegrees: Float) {
        // Re-apply FIT_CENTER before rotating so a zoomed/panned image does not keep an old pan
        // offset as the pivot. PhotoView will center the drawable inside its fixed viewport.
        runCatching { setScale(minimumScale, false) }

        val startRotation = visualRotation
        val targetRotation = normalizeDegrees(visualRotation + stepDegrees)
        val startScale = viewportFitScale
        val targetScale = computeViewportFitScale(targetRotation)

        pivotX = width / 2f
        pivotY = height / 2f
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                val interpolatedRotation = interpolateRotation(startRotation, targetRotation, stepDegrees, t)
                val interpolatedScale = startScale + ((targetScale - startScale) * t)
                rotation = interpolatedRotation
                scaleX = interpolatedScale
                scaleY = interpolatedScale
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    visualRotation = targetRotation
                    viewportFitScale = targetScale
                    rotation = visualRotation
                    scaleX = viewportFitScale
                    scaleY = viewportFitScale
                }
            })
            start()
        }
    }

    private fun computeViewportFitScale(rotationDegrees: Float): Float {
        val rect = displayRect ?: drawable?.let {
            RectF(0f, 0f, it.intrinsicWidth.toFloat(), it.intrinsicHeight.toFloat())
        } ?: return 1f
        val quarterTurn = isQuarterTurn(rotationDegrees)
        val rotatedWidth = if (quarterTurn) rect.height() else rect.width()
        val rotatedHeight = if (quarterTurn) rect.width() else rect.height()
        if (rotatedWidth <= 0f || rotatedHeight <= 0f || width <= 0 || height <= 0) return 1f
        return minOf(width / rotatedWidth, height / rotatedHeight, 1f)
    }

    private fun angleBetweenFirstTwoPointers(event: MotionEvent): Float? {
        if (event.pointerCount < 2) return null
        val dx = event.getX(1) - event.getX(0)
        val dy = event.getY(1) - event.getY(0)
        return Math.toDegrees(kotlin.math.atan2(dy.toDouble(), dx.toDouble())).toFloat()
    }

    private fun spanBetweenFirstTwoPointers(event: MotionEvent): Float? {
        if (event.pointerCount < 2) return null
        return hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    }

    private fun shortestAngleDelta(from: Float, to: Float): Float {
        var delta = to - from
        if (delta > 180f) delta -= 360f
        if (delta < -180f) delta += 360f
        return delta
    }

    private fun interpolateRotation(start: Float, target: Float, step: Float, t: Float): Float {
        val end = if (step > 0f && target < start) target + 360f else if (step < 0f && target > start) target - 360f else target
        return start + ((end - start) * t)
    }

    private fun normalizeDegrees(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

    private fun isQuarterTurn(degrees: Float): Boolean {
        val normalized = normalizeDegrees(degrees).toInt()
        return normalized == 90 || normalized == 270
    }

    private companion object {
        const val TRIGGER_DEGREES = 35f
        const val PINCH_DOMINANCE_RATIO = 0.16f
        const val SNAP_DURATION_MS = 220L
    }
}
