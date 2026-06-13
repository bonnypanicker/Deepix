package com.devomind.gallerysearch

import android.view.ScaleGestureDetector
import kotlin.math.roundToInt

class ThumbnailScaleGestureListener(
    private val initialColumns: Int,
    private val onColumnCountChanged: (Int) -> Unit
) : ScaleGestureDetector.SimpleOnScaleGestureListener() {

    private var currentColumns = initialColumns
    private var accumulatedScale = 1.0f

    // We can tune these to require more or less finger movement
    private val zoomInThreshold = 1.25f
    private val zoomOutThreshold = 0.8f

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        accumulatedScale = 1.0f
        return super.onScaleBegin(detector)
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        accumulatedScale *= detector.scaleFactor

        var changed = false
        if (accumulatedScale > zoomInThreshold) {
            // Zooming in -> make thumbnails bigger -> fewer columns
            val newCols = (currentColumns - 1).coerceAtLeast(DesignTokens.GRID_MIN_COLUMNS)
            if (newCols != currentColumns) {
                currentColumns = newCols
                changed = true
            }
            accumulatedScale = 1.0f // reset for next tick
        } else if (accumulatedScale < zoomOutThreshold) {
            // Zooming out -> make thumbnails smaller -> more columns
            val newCols = (currentColumns + 1).coerceAtMost(DesignTokens.GRID_MAX_COLUMNS)
            if (newCols != currentColumns) {
                currentColumns = newCols
                changed = true
            }
            accumulatedScale = 1.0f // reset for next tick
        }

        if (changed) {
            onColumnCountChanged(currentColumns)
        }

        return true
    }

    fun updateCurrentColumns(columns: Int) {
        currentColumns = columns
    }
}
