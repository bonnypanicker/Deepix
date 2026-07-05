package com.devomind.gallerysearch

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pure bitmap operations for the in-app editor. Each returns a NEW bitmap; callers own recycling of
 * the previous one via the editor's undo stack.
 */
object PhotoEditOps {

    private fun paint() = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    fun flipHorizontal(src: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /** Crops [src] to the given pixel rect (clamped to bounds). */
    fun crop(src: Bitmap, rect: Rect): Bitmap {
        val l = rect.left.coerceIn(0, src.width - 1)
        val t = rect.top.coerceIn(0, src.height - 1)
        val r = rect.right.coerceIn(l + 1, src.width)
        val b = rect.bottom.coerceIn(t + 1, src.height)
        return Bitmap.createBitmap(src, l, t, r - l, b - t)
    }

    /**
     * Perspective-corrects [src] using four source corners (in bitmap pixels, order TL, TR, BR, BL)
     * and warps the enclosed quad into an upright rectangle — deskews documents / photos of screens.
     */
    fun perspective(src: Bitmap, corners: FloatArray): Bitmap {
        require(corners.size == 8)
        val (tlx, tly) = corners[0] to corners[1]
        val (trx, tryy) = corners[2] to corners[3]
        val (brx, bry) = corners[4] to corners[5]
        val (blx, bly) = corners[6] to corners[7]

        val widthTop = hypot((trx - tlx).toDouble(), (tryy - tly).toDouble())
        val widthBottom = hypot((brx - blx).toDouble(), (bry - bly).toDouble())
        val heightLeft = hypot((blx - tlx).toDouble(), (bly - tly).toDouble())
        val heightRight = hypot((brx - trx).toDouble(), (bry - tryy).toDouble())

        val outW = max(widthTop, widthBottom).roundToInt().coerceAtLeast(1)
        val outH = max(heightLeft, heightRight).roundToInt().coerceAtLeast(1)

        val dst = floatArrayOf(
            0f, 0f,
            outW.toFloat(), 0f,
            outW.toFloat(), outH.toFloat(),
            0f, outH.toFloat()
        )
        val m = Matrix()
        m.setPolyToPoly(corners, 0, dst, 0, 4)

        val out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, m, paint())
        return out
    }

    /** Bakes a brightness/contrast/saturation adjustment into a new bitmap. */
    fun applyColorMatrix(src: Bitmap, matrix: ColorMatrix): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val p = paint().apply { colorFilter = ColorMatrixColorFilter(matrix) }
        Canvas(out).drawBitmap(src, 0f, 0f, p)
        return out
    }

    /**
     * Builds a color matrix from UI values.
     * @param brightness -100..100 (offset), @param contrast -100..100, @param saturation 0..200 (100 = normal)
     */
    fun colorMatrix(brightness: Float, contrast: Float, saturation: Float): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation((saturation / 100f).coerceIn(0f, 2f))

        val c = 1f + (contrast / 100f) // 0..2
        val t = (1f - c) * 128f
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    c, 0f, 0f, 0f, t,
                    0f, c, 0f, 0f, t,
                    0f, 0f, c, 0f, t,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        val b = brightness
        cm.postConcat(
            ColorMatrix(
                floatArrayOf(
                    1f, 0f, 0f, 0f, b,
                    0f, 1f, 0f, 0f, b,
                    0f, 0f, 1f, 0f, b,
                    0f, 0f, 0f, 1f, 0f
                )
            )
        )
        return cm
    }

    /** One-tap document look: strong contrast, lifted brightness, reduced colour for legible text. */
    fun documentMatrix(): ColorMatrix = colorMatrix(brightness = 12f, contrast = 42f, saturation = 35f)

    /**
     * A freehand stroke stored resolution-independently: [points] are x,y pairs normalized to
     * 0..1 of the image, and [widthFraction] is the stroke width as a fraction of image width — so
     * it can be replayed identically at any resolution.
     */
    class Stroke(val points: FloatArray, val color: Int, val widthFraction: Float)

    /** Draws normalized [strokes] onto a copy of [src] at its native resolution. */
    fun drawStrokes(src: Bitmap, strokes: List<Stroke>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        val w = out.width
        val h = out.height
        for (s in strokes) {
            if (s.points.size < 2) continue
            p.color = s.color
            p.strokeWidth = (s.widthFraction * w).coerceAtLeast(1f)
            val path = android.graphics.Path()
            path.moveTo(s.points[0] * w, s.points[1] * h)
            var i = 2
            while (i + 1 < s.points.size) {
                path.lineTo(s.points[i] * w, s.points[i + 1] * h)
                i += 2
            }
            if (s.points.size == 2) {
                // Single tap → dot.
                canvas.drawPoint(s.points[0] * w, s.points[1] * h, p.apply { style = Paint.Style.FILL })
                p.style = Paint.Style.STROKE
            } else {
                canvas.drawPath(path, p)
            }
        }
        return out
    }
}
