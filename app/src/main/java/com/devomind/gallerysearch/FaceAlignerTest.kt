package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Log

object FaceAlignerTest {

    /** Synthetic invariants. Run from the debug harness. */
    fun check(context: Context): String = run {
        val report = StringBuilder()
        val fbi = BitmapFactory.decodeFile("/dev/null")?.recycle()
        val srcBitmap = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        for (x in 0 until 200) for (y in 0 until 200) srcBitmap.setPixel(x, y, x + y * 200)

        // Identity landmark mapping: source == destination landmarks
        val landmarks = arrayOf(
            floatArrayOf(38.2946f, 51.6963f),
            floatArrayOf(73.5318f, 51.5014f),
            floatArrayOf(56.0252f, 71.7366f),
            floatArrayOf(41.5493f, 92.3655f),
            floatArrayOf(70.7299f, 92.2041f),
        )
        val dst = landmarks // exactly canonical layout

        val matrixResult = FaceAligner.runCatching { FaceAligner.align(srcBitmap, landmarks) }
        report.append("identity align ok=${matrixResult.isSuccess}")

        // Wide-spread sanity: if source has wide bbox instead of canonical landmarks, the aligner should still produce a bitmap.
        val wide = arrayOf(
            floatArrayOf(10f, 10f),
            floatArrayOf(190f, 10f),
            floatArrayOf(100f, 100f),
            floatArrayOf(50f, 160f),
            floatArrayOf(150f, 160f)
        )
        val wideResult = runCatching { FaceAligner.align(srcBitmap, wide) }
        report.append("; wideLandscape eos=${wideResult.isSuccess}")

        report.toString()
    }
}
