package com.devomind.gallerysearch

import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * PhotoView with a two-finger twist gesture that rotates the photo, snapping to the nearest
 * right angle on release.
 *
 * Rotation must live in PhotoView's image matrix, not in View.rotation. Rotating the Android view
 * turns the whole portrait viewport into a rotated drawing layer, which clips a portrait image when
 * it becomes landscape and makes zooming look trapped inside an old rectangular container.
 */
class RotatablePhotoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : PhotoView(context, attrs, defStyle) {

    private var gestureAccumulation = 0f
    private var startRotation = 0f
    private var imageRotation = 0f
    private var engaged = false
    private var snapAnimator: ValueAnimator? = null

    private val rotationDetector = RotationGestureDetector(object : RotationGestureDetector.Listener {
        override fun onRotationBegin() {
            snapAnimator?.cancel()
            gestureAccumulation = 0f
            startRotation = imageRotation
            engaged = false
        }

        override fun onRotation(deltaDegrees: Float) {
            gestureAccumulation += deltaDegrees
            if (!engaged && abs(gestureAccumulation) < ACTIVATION_THRESHOLD_DEGREES) return
            engaged = true
            applyImageRotation(startRotation + gestureAccumulation)
        }

        override fun onRotationEnd() {
            if (!engaged) return
            engaged = false
            snapToNearestRightAngle()
        }
    })

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Once a second finger lands, claim the gesture so ViewPager2 cannot convert the twist into
        // a page swipe. The framework clears this on the next ACTION_DOWN.
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        // Coordinates stay in the stable screen/view frame because only the image matrix rotates.
        rotationDetector.onTouchEvent(event)
        return super.dispatchTouchEvent(event)
    }

    /** Clears any temporary rotation. Call when a new image is bound into this view. */
    fun resetRotation() {
        snapAnimator?.cancel()
        snapAnimator = null
        engaged = false
        gestureAccumulation = 0f
        imageRotation = 0f
        setRotationTo(0f)
    }

    private fun applyImageRotation(degrees: Float) {
        imageRotation = normalizeDegrees(degrees)
        setRotationTo(imageRotation)
    }

    private fun snapToNearestRightAngle() {
        val current = imageRotation
        val target = (current / 90f).roundToInt() * 90f
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(current, target).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyImageRotation(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    applyImageRotation(target)
                }
            })
            start()
        }
    }

    private fun normalizeDegrees(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f

    private companion object {
        const val ACTIVATION_THRESHOLD_DEGREES = 10f
        const val SNAP_DURATION_MS = 220L
    }
}
