package com.devomind.gallerysearch

import android.content.ContentResolver
import android.net.Uri
import android.provider.MediaStore

/**
 * Queries MediaStore for the width/height of the given photo URIs in one pass, converted into
 * the *face-detection bitmap's* coordinate space: EXIF orientation applied (w/h swapped on
 * 90°/270°), then downsampled by the same power-of-two inSampleSize rule
 * [GalleryRepository.loadBitmapForFaceDetection] uses toward
 * [GalleryRepository.FaceDetectionMaxEdge]. Returns a map keyed by the uri string →
 * intArrayOf [width, height]. [FaceCropTransform] and [OriginalFaceCoverDecoder] scale the
 * stored face bbox (which FaceAnalyzer measured in that detection space) using these dims —
 * passing raw MediaStore dims double-downscales the crop and the cover shows background
 * instead of the face.
 */
object FaceDetectionDims {

    fun resolve(resolver: ContentResolver, uris: Set<String>): Map<String, IntArray> {
        if (uris.isEmpty()) return emptyMap()
        val idToUri = LinkedHashMap<Long, String>()
        for (u in uris) {
            val id = runCatching {
                Uri.parse(u).lastPathSegment?.toLongOrNull()
            }.getOrNull()
            if (id != null && id > 0) idToUri[id] = u
        }
        if (idToUri.isEmpty()) return emptyMap()
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val ids = idToUri.keys.joinToString(",")
        val out = HashMap<String, IntArray>(idToUri.size)
        val cursor = runCatching {
            resolver.query(
                collection,
                arrayOf(
                    MediaStore.Images.Media._ID,
                    MediaStore.Images.Media.WIDTH,
                    MediaStore.Images.Media.HEIGHT,
                    MediaStore.Images.Media.ORIENTATION
                ),
                "${MediaStore.Images.Media._ID} IN ($ids)",
                null,
                null
            )
        }.getOrNull() ?: return out
        cursor.use {
            val idIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val wIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val hIdx = it.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val oIdx = it.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            while (it.moveToNext()) {
                val id = it.getLong(idIdx)
                var w = it.getInt(wIdx)
                var h = it.getInt(hIdx)
                val uriStr = idToUri[id] ?: continue
                if (w <= 0 || h <= 0) continue
                // Match the oriented detection bitmap: 90°/270° rotations swap width/height.
                val orientation = if (oIdx >= 0) it.getInt(oIdx) else 0
                if (orientation == 90 || orientation == 270) {
                    val tmp = w; w = h; h = tmp
                }
                // Mirror decodeOrientedBitmap's inSampleSize loop so dims describe the bitmap
                // YuNet actually ran on, not the full-resolution file.
                var sample = 1
                while (maxOf(w, h) / sample > GalleryRepository.FaceDetectionMaxEdge) sample *= 2
                out[uriStr] = intArrayOf(w / sample, h / sample)
            }
        }
        return out
    }
}
