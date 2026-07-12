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
 * PhotoView with a two-finger twist gesture that temporarily rotates the photo, snapping to the
 * nearest 90° on release (0 / 90 / 180 / 270).
 *
 * Rotation is applied through [View.setRotation] (about the view centre) rather than PhotoView's
 * matrix rotation, which pivots on the top-left corner. Using the view property also keeps
 * pinch-zoom and pan working, since Android transforms incoming touch coordinates back into the
 * view's un-rotated space before PhotoView's attacher sees them.
 *
 * The rotation is purely visual and is reset whenever a new image is bound.
 */
class RotatablePhotoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : PhotoView(context, attrs, defStyle) {

    private var gestureAccumulation = 0f
    private var startRotation = 0f
    private var engaged = false
    private var snapAnimator: ValueAnimator? = null

    private val rotationDetector = RotationGestureDetector(object : RotationGestureDetector.Listener {
        override fun onRotationBegin() {
            snapAnimator?.cancel()
            gestureAccumulation = 0f
            startRotation = rotation
            engaged = false
        }

        override fun onRotation(deltaDegrees: Float) {
            gestureAccumulation += deltaDegrees
            if (!engaged && abs(gestureAccumulation) < ACTIVATION_THRESHOLD_DEGREES) return
            engaged = true
            applyRotation(startRotation + gestureAccumulation)
        }

        override fun onRotationEnd() {
            if (!engaged) return
            engaged = false
            snapToNearestRightAngle()
        }
    })

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Once a second finger lands, claim the gesture so ViewPager2 can't turn it into a page
        // swipe. The framework auto-clears this on the next ACTION_DOWN, so it also covers the
        // residual one-finger phase after a finger lifts (which otherwise collided with paging).
        if (event.actionMasked == MotionEvent.ACTION_POINTER_DOWN && event.pointerCount >= 2) {
            parent?.requestDisallowInterceptTouchEvent(true)
        }

        // Feed the detector using coordinates mapped back into the parent's (un-rotated) frame.
        // Because we rotate THIS view via setRotation, the framework counter-rotates the incoming
        // touch coordinates; measuring the twist in that moving frame fed our own rotation back into
        // the measurement and made the image vibrate. getMatrix() (local->parent) undoes exactly
        // that counter-rotation, so the angle is measured in a stable frame.
        if (rotation != 0f) {
            val transformed = MotionEvent.obtain(event)
            transformed.transform(matrix)
            rotationDetector.onTouchEvent(transformed)
            transformed.recycle()
        } else {
            rotationDetector.onTouchEvent(event)
        }
        return super.dispatchTouchEvent(event)
    }

    /** Clears any temporary rotation. Call when a new image is bound into this view. */
    fun resetRotation() {
        snapAnimator?.cancel()
        snapAnimator = null
        engaged = false
        gestureAccumulation = 0f
        rotation = 0f
    }

    /** Rotates the view at its natural size — no containment scaling. */
    private fun applyRotation(degrees: Float) {
        rotation = degrees
    }

    private fun snapToNearestRightAngle() {
        val current = rotation
        val target = (current / 90f).roundToInt() * 90f
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(current, target).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { applyRotation(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // Keep the value bounded so repeated twists don't accumulate large numbers;
                    // 360 and 0 are visually identical so this causes no jump.
                    applyRotation(((target % 360f) + 360f) % 360f)
                }
            })
            start()
        }
    }

    private companion object {
        const val ACTIVATION_THRESHOLD_DEGREES = 10f
        const val SNAP_DURATION_MS = 220L
    }
}
