package com.devomind.gallerysearch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.bumptech.glide.Glide
import com.bumptech.glide.Registry
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.Options
import com.bumptech.glide.load.ResourceDecoder
import com.bumptech.glide.load.engine.Resource
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapResource
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.target.Target
import com.caverock.androidsvg.SVG
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Generates the typed GlideAPI entry point and turns off manifest parsing, which Glide falls
 * back to (with a warning and slower first-load setup) when no AppGlideModule is present.
 *
 * Extra format decoders Glide lacks out of the box:
 * - AVIF below API 31 is supplied by glide:avif-integration. Its @GlideModule-annotated
 *   LibraryGlideModule is folded into the generated Glide setup by the kapt compiler, so the
 *   disabled manifest parsing does not exclude it.
 * - SVG is rasterized to a Bitmap by [SvgBitmapDecoder], registered at the tail of the decoder
 *   list. Glide falls through to later decoders when an earlier one fails, so this only ever
 *   runs for streams BitmapFactory already rejected — ordinary formats never reach it.
 */
@GlideModule
class GalleryGlideModule : AppGlideModule() {
    override fun isManifestParsingEnabled(): Boolean = false

    override fun registerComponents(context: Context, glide: Glide, registry: Registry) {
        registry.append(InputStream::class.java, Bitmap::class.java, SvgBitmapDecoder(glide.bitmapPool))
    }
}

/**
 * Rasterizes SVG streams to Bitmaps so every surface (grid, viewer, covers) treats an SVG like
 * any other image — no hardware-acceleration or PhotoView special-casing that a PictureDrawable
 * pipeline would need. [handles] claims only streams whose head looks like an SVG document, so
 * undecodable binary formats (e.g. TIFF, which Android cannot decode at any API level) still
 * fall through to Glide's error placeholder.
 */
class SvgBitmapDecoder(private val bitmapPool: BitmapPool) : ResourceDecoder<InputStream, Bitmap> {

    override fun handles(source: InputStream, options: Options): Boolean {
        if (!source.markSupported()) return false
        source.mark(SNIFF_BYTES)
        return try {
            val buffer = ByteArray(SNIFF_BYTES)
            var read = 0
            while (read < buffer.size) {
                val n = source.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            read > 0 && String(buffer, 0, read, Charsets.UTF_8).contains(SVG_TAG)
        } catch (e: Exception) {
            false
        } finally {
            try {
                source.reset()
            } catch (e: Exception) {
                // Nothing useful to do — the stream is unusable for us either way.
            }
        }
    }

    override fun decode(source: InputStream, width: Int, height: Int, options: Options): Resource<Bitmap> {
        val svg = SVG.getFromInputStream(source)

        val intrinsicW = svg.documentWidth.takeIf { it > 0f } ?: svg.documentViewBox?.width() ?: 0f
        val intrinsicH = svg.documentHeight.takeIf { it > 0f } ?: svg.documentViewBox?.height() ?: 0f

        // Glide hands us the resolved target size (override/View size). Rasterize at least that
        // large, preserving aspect; CenterCrop/FitCenter transformations take it from there.
        var outW: Int
        var outH: Int
        if (width != Target.SIZE_ORIGINAL && height != Target.SIZE_ORIGINAL) {
            if (intrinsicW > 0f && intrinsicH > 0f) {
                val scale = max(width / intrinsicW, height / intrinsicH)
                outW = (intrinsicW * scale).roundToInt()
                outH = (intrinsicH * scale).roundToInt()
            } else {
                outW = width
                outH = height
            }
        } else if (intrinsicW > 0f && intrinsicH > 0f) {
            outW = intrinsicW.roundToInt()
            outH = intrinsicH.roundToInt()
        } else {
            outW = FALLBACK_SIZE_PX
            outH = FALLBACK_SIZE_PX
        }
        outW = outW.coerceIn(1, MAX_DIMENSION_PX)
        outH = outH.coerceIn(1, MAX_DIMENSION_PX)

        svg.documentWidth = outW.toFloat()
        svg.documentHeight = outH.toFloat()

        val bitmap = bitmapPool.get(outW, outH, Bitmap.Config.ARGB_8888)
        // Pooled bitmaps come back dirty; SVGs rely on transparency, so start clean.
        bitmap.eraseColor(Color.TRANSPARENT)
        svg.renderToCanvas(Canvas(bitmap))
        return BitmapResource.obtain(bitmap, bitmapPool)!!
    }

    private companion object {
        /** Enough head to clear an XML prolog, comments, and DOCTYPE before the root element. */
        const val SNIFF_BYTES = 4096
        const val SVG_TAG = "<svg"
        const val FALLBACK_SIZE_PX = 512
        const val MAX_DIMENSION_PX = 4096
    }
}
