package com.devomind.gallerysearch

import android.view.ScaleGestureDetector

/**
 * Pinch-to-resize thumbnails. Emits a single discrete step per threshold crossing: [onZoom] is
 * called with `true` when the user spreads their fingers (zoom in → bigger thumbnails) and `false`
 * when they pinch together (zoom out → smaller thumbnails). The caller decides what a step means —
 * fewer/more grid columns, or a bigger/smaller collage scale.
 */
class ThumbnailScaleGestureListener(
    private val onZoom: (zoomIn: Boolean) -> Unit
) : ScaleGestureDetector.SimpleOnScaleGestureListener() {

    private var accumulatedScale = 1.0f

    // We can tune these to require more or less finger movement.
    private val zoomInThreshold = 1.25f
    private val zoomOutThreshold = 0.8f

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        accumulatedScale = 1.0f
        return super.onScaleBegin(detector)
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        accumulatedScale *= detector.scaleFactor

        if (accumulatedScale > zoomInThreshold) {
            onZoom(true)
            accumulatedScale = 1.0f // reset for next tick
        } else if (accumulatedScale < zoomOutThreshold) {
            onZoom(false)
            accumulatedScale = 1.0f // reset for next tick
        }

        return true
    }
}
