package com.devomind.gallerysearch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/**
 * Draws a circular magnifier loupe showing a zoomed patch of the source image around a focus point,
 * with a crosshair — shared by the crop and perspective overlays so corner placement is precise.
 */
object EditorMagnifier {

    private const val ACCENT = 0xFF3B9EFF.toInt()
    private const val ZOOM = 1.9f

    private val bitmapPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = ACCENT
    }
    private val ringInner = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = 0x66FFFFFF
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = ACCENT
    }

    /**
     * @param bounds on-screen rect of the fit-center image (same space as [focusX]/[focusY])
     * @param focusX,focusY the point to magnify, in view coordinates
     */
    fun draw(
        canvas: Canvas,
        source: Bitmap,
        bounds: RectF,
        focusX: Float,
        focusY: Float,
        density: Float
    ) {
        if (source.isRecycled || bounds.width() <= 0f || source.width <= 0) return

        val radius = 58f * density
        val pad = 10f * density
        val fingerClear = 52f * density

        // Position the bubble above the finger; drop below if there's no room up top.
        var mx = focusX
        var my = focusY - radius - fingerClear
        if (my - radius < pad) my = focusY + radius + fingerClear
        mx = mx.coerceIn(radius + pad, canvas.width - radius - pad)
        my = my.coerceIn(radius + pad, canvas.height - radius - pad)

        val dispScale = bounds.width() / source.width          // bitmap px -> screen px
        if (dispScale <= 0f) return
        val magScale = dispScale * ZOOM
        val fbx = (focusX - bounds.left) / dispScale            // focus in bitmap px
        val fby = (focusY - bounds.top) / dispScale

        val m = Matrix().apply {
            setScale(magScale, magScale)
            postTranslate(mx - fbx * magScale, my - fby * magScale)
        }

        val save = canvas.save()
        val clip = Path().apply { addCircle(mx, my, radius, Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawColor(Color.BLACK)                            // backdrop beyond image edges
        canvas.drawBitmap(source, m, bitmapPaint)
        canvas.restoreToCount(save)

        // Crosshair at the exact focus.
        cross.strokeWidth = 1.5f * density
        val ch = radius * 0.32f
        canvas.drawLine(mx - ch, my, mx + ch, my, cross)
        canvas.drawLine(mx, my - ch, mx, my + ch, cross)

        ringInner.strokeWidth = 1f * density
        canvas.drawCircle(mx, my, radius - 1.5f * density, ringInner)
        ring.strokeWidth = 2.5f * density
        canvas.drawCircle(mx, my, radius, ring)
    }
}
