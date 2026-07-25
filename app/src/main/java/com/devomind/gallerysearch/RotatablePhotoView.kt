package com.devomind.gallerysearch

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.animation.DecelerateInterpolator
import com.github.chrisbanes.photoview.PhotoView
import kotlin.math.abs
import kotlin.math.hypot

/**
 * PhotoView with a two-finger twist trigger that rotates the photo in 90-degree steps.
 *
 * The rotation is applied to the BITMAP, not the view: the twist plays a short view-level
 * rotate+contain animation purely as a visual, then swaps in a losslessly rotated copy of the
 * bitmap and resets every view transform. PhotoView then treats the rotated image as the image —
 * pinch-zoom, pan, fling and the viewer's swipe-to-dismiss all behave exactly as for an unrotated
 * photo (no rotated viewport, no clipping rectangle, no transformed touch coordinates).
 *
 * Gesture arbitration (single mode per gesture, locked until all fingers lift):
 *  - Twist only arms while the photo is at fit scale — once zoomed, two fingers always mean zoom.
 *  - The moment pinch movement dominates, the gesture is disqualified for rotation — and the
 *    disqualification is sticky across finger re-lands within the same gesture, so a zoom that
 *    drifts angularly can never fire a surprise rotation mid-stream.
 *  - While the snap animation runs it is the only writer of the view transform: all touch input
 *    is swallowed until it completes, so PhotoView can never zoom against a mid-rotation view
 *    (whose focal point would be computed in unrotated screen coordinates).
 *  - The twist must cross [TRIGGER_DEGREES] with the fingers a sensible distance apart.
 */
class RotatablePhotoView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : PhotoView(context, attrs, defStyle) {

    private var twistStartAngle = 0f
    private var twistStartSpan = 0f
    private var twistActive = false
    private var twistDisqualified = false
    private var rotationTriggered = false
    private var snapAnimator: ValueAnimator? = null

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // While the snap animation owns the view transform, swallow ALL input. Letting events
        // through would have PhotoView pinch-zooming its matrix while the animator interpolates
        // rotation/scale on the same view (two writers per frame), and the pinch focal point
        // would be computed in unrotated coordinates against a mid-rotation view.
        if (snapAnimator?.isRunning == true) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                endTwist()
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (rotationTriggered) return true
                if (event.pointerCount >= 2) {
                    parent?.requestDisallowInterceptTouchEvent(true)
                    beginTwist(event)
                }
            }
            MotionEvent.ACTION_MOVE -> {
                if (rotationTriggered) return true // eat the rest of the gesture post-rotation
                if (twistActive && !twistDisqualified && event.pointerCount >= 2) {
                    val step = detectTwistStep(event)
                    if (step != null) {
                        rotationTriggered = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                        // Reset PhotoView's in-flight pinch/drag state before transforming, so the
                        // ongoing fingers can't zoom/pan the freshly rotated image.
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        rotateOneStep(step)
                        return true
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (rotationTriggered) return true
                // A finger lifted but the gesture continues: drop the twist baselines (they're
                // re-measured if a second finger returns) but KEEP the mode lock. Once scale has
                // won a gesture, it stays won until every finger is up.
                twistActive = false
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val consume = rotationTriggered
                endTwist()
                if (consume) return true
            }
        }
        return super.dispatchTouchEvent(event)
    }

    /** Clears any in-flight snap animation and view transforms. Call when a new image is bound. */
    fun resetRotation() {
        snapAnimator?.cancel()
        snapAnimator = null
        twistActive = false
        twistDisqualified = false
        rotationTriggered = false
        rotation = 0f
        scaleX = 1f
        scaleY = 1f
    }

    /** Rotates the photo 90 degrees clockwise, baking the rotation into the bitmap. */
    fun rotateClockwise() {
        rotateOneStep(90f)
    }

    private fun beginTwist(event: MotionEvent) {
        val angle = angleBetweenFirstTwoPointers(event) ?: return
        val span = spanBetweenFirstTwoPointers(event) ?: return
        twistStartAngle = angle
        twistStartSpan = span
        // Sticky mode lock: once this gesture was claimed by scale (pinch dominated, photo was/is
        // zoomed) it stays disqualified — a finger re-landing mid-gesture must NOT re-arm rotation.
        // Fingers too close produce jittery angles; a zoomed photo reserves two fingers for zoom.
        twistDisqualified = twistDisqualified || span < minTwistSpanPx() || isZoomedIn()
        twistActive = true
    }

    /** Returns +90/-90 once the twist threshold is crossed cleanly, else null. */
    private fun detectTwistStep(event: MotionEvent): Float? {
        val angle = angleBetweenFirstTwoPointers(event) ?: return null
        val span = spanBetweenFirstTwoPointers(event) ?: return null

        // Once the gesture reads as a pinch (or the photo zooms), rotation is off until fingers lift.
        val pinchRatio = if (twistStartSpan > 0f) abs(span - twistStartSpan) / twistStartSpan else 0f
        if (pinchRatio > PINCH_DOMINANCE_RATIO || isZoomedIn()) {
            twistDisqualified = true
            return null
        }

        val twist = shortestAngleDelta(twistStartAngle, angle)
        if (abs(twist) < TRIGGER_DEGREES) return null
        return if (twist > 0f) 90f else -90f
    }

    private fun endTwist() {
        twistActive = false
        twistDisqualified = false
        rotationTriggered = false
    }

    private fun isZoomedIn(): Boolean =
        runCatching { scale > minimumScale * ZOOM_TOLERANCE }.getOrDefault(false)

    /**
     * Animates a 90-degree turn at view level, then commits it by swapping in a rotated bitmap and
     * clearing the view transforms. The animation's end scale is the exact FIT_CENTER scale of the
     * rotated image, so the swap is seamless.
     */
    private fun rotateOneStep(stepDegrees: Float) {
        if (snapAnimator?.isRunning == true) return
        val bitmap = currentBitmap() ?: return

        // Drop any pan/zoom so the pivot is the visual center of the fitted photo.
        runCatching { setScale(minimumScale, false) }

        val targetScale = rotatedContainScale()
        pivotX = width / 2f
        pivotY = height / 2f
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SNAP_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val t = animator.animatedValue as Float
                rotation = stepDegrees * t
                val s = 1f + ((targetScale - 1f) * t)
                scaleX = s
                scaleY = s
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false

                override fun onAnimationCancel(animation: android.animation.Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // cancel() also fires onAnimationEnd; a cancelled snap (view recycled /
                    // new image bound) must not bake-and-swap the stale bitmap.
                    if (!cancelled) commitRotation(bitmap, stepDegrees)
                }
            })
            start()
        }
    }

    /** Bakes the step into the bitmap and restores a clean, unrotated view. */
    private fun commitRotation(source: Bitmap, stepDegrees: Float) {
        val rotated = runCatching {
            val matrix = Matrix().apply { postRotate(stepDegrees) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        }.getOrNull()
        rotation = 0f
        scaleX = 1f
        scaleY = 1f
        if (rotated != null) {
            setImageBitmap(rotated) // resets PhotoView's matrix → FIT_CENTER of the rotated image
        }
    }

    /**
     * View scale that makes the 90-degree-rotated fitted photo land exactly on the FIT_CENTER size
     * the swapped bitmap will get (uncapped: narrow images may scale up, matching FIT_CENTER).
     */
    private fun rotatedContainScale(): Float {
        val rect = displayRect ?: return 1f
        if (rect.width() <= 0f || rect.height() <= 0f || width <= 0 || height <= 0) return 1f
        return minOf(width / rect.height(), height / rect.width())
    }

    /** The currently displayed bitmap (fast path), or the drawable rendered into one. */
    private fun currentBitmap(): Bitmap? {
        val d: Drawable = drawable ?: return null
        (d as? BitmapDrawable)?.bitmap?.let { return it }
        val w = d.intrinsicWidth
        val h = d.intrinsicHeight
        if (w <= 0 || h <= 0) return null
        return runCatching {
            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val saved = android.graphics.Rect(d.bounds)
            d.setBounds(0, 0, w, h)
            d.draw(Canvas(bmp))
            d.bounds = saved
            bmp
        }.getOrNull()
    }

    private fun minTwistSpanPx(): Float = MIN_TWIST_SPAN_DP * resources.displayMetrics.density

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

    private companion object {
        const val TRIGGER_DEGREES = 35f
        const val PINCH_DOMINANCE_RATIO = 0.16f
        const val ZOOM_TOLERANCE = 1.05f
        const val MIN_TWIST_SPAN_DP = 64f
        const val SNAP_DURATION_MS = 220L
    }
}
