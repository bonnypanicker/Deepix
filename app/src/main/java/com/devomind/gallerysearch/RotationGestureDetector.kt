package com.devomind.gallerysearch

import android.view.MotionEvent
import kotlin.math.atan2

/**
 * Detects a two-finger twist gesture and reports the incremental rotation (in degrees, clockwise
 * positive) between move events. It only tracks the first two pointers, so it composes cleanly with
 * PhotoView's own pinch-to-zoom / pan handling on the same touch stream.
 */
class RotationGestureDetector(private val listener: Listener) {

    interface Listener {
        fun onRotationBegin()
        fun onRotation(deltaDegrees: Float)
        fun onRotationEnd()
    }

    private var pointerId1 = INVALID_POINTER_ID
    private var pointerId2 = INVALID_POINTER_ID
    private var previousAngle = 0f
    private var active = false

    fun onTouchEvent(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pointerId1 = event.getPointerId(event.actionIndex)
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (pointerId1 != INVALID_POINTER_ID && pointerId2 == INVALID_POINTER_ID) {
                    pointerId2 = event.getPointerId(event.actionIndex)
                    val angle = angleBetweenPointers(event) ?: return
                    previousAngle = angle
                    active = true
                    listener.onRotationBegin()
                }
            }

            MotionEvent.ACTION_MOVE -> {
                if (!active) return
                val current = angleBetweenPointers(event) ?: return
                var delta = current - previousAngle
                if (delta > 180f) delta -= 360f
                if (delta < -180f) delta += 360f
                previousAngle = current
                listener.onRotation(delta)
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val upId = event.getPointerId(event.actionIndex)
                if (upId == pointerId1 || upId == pointerId2) {
                    end()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> end()
        }
    }

    private fun end() {
        if (active) {
            active = false
            listener.onRotationEnd()
        }
        pointerId1 = INVALID_POINTER_ID
        pointerId2 = INVALID_POINTER_ID
    }

    /** Screen-space angle of the line joining the two active pointers, in degrees. */
    private fun angleBetweenPointers(event: MotionEvent): Float? {
        val index1 = event.findPointerIndex(pointerId1)
        val index2 = event.findPointerIndex(pointerId2)
        if (index1 < 0 || index2 < 0) return null
        val dx = (event.getX(index2) - event.getX(index1)).toDouble()
        val dy = (event.getY(index2) - event.getY(index1)).toDouble()
        return Math.toDegrees(atan2(dy, dx)).toFloat()
    }

    private companion object {
        const val INVALID_POINTER_ID = -1
    }
}
