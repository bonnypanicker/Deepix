package com.devomind.gallerysearch
import android.content.ContentResolver
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.net.toFile
import androidx.exifinterface.media.ExifInterface
import com.devomind.gallerysearch.data.ExifMetadata
import java.io.InputStream

object ExifExtractor {
    fun extract(contentResolver: ContentResolver, uri: Uri): ExifMetadata? {
        return try {
            val inputStream = contentResolver.openInputStream(uri) ?: return null
            val exif = ExifInterface(inputStream)
            val model = exif.getAttribute(ExifInterface.TAG_MODEL) ?: exif.getAttribute(ExifInterface.TAG_MAKE)
            val aperture = exif.getAttribute(ExifInterface.TAG_F_NUMBER)
            val iso = exif.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)
            val shutter = exif.getAttribute(ExifInterface.TAG_EXPOSURE_TIME)
            val focal = exif.getAttribute(ExifInterface.TAG_FOCAL_LENGTH)
            val wb = exif.getAttribute(ExifInterface.TAG_WHITE_BALANCE)
            val latLong = exif.latLong?.let { Pair(it[0], it[1]) }
            inputStream.close()
            ExifMetadata(
                uri = uri.toString(),
                cameraModel = model,
                aperture = aperture,
                iso = iso,
                shutterSpeed = shutter,
                focalLength = focal,
                whiteBalance = wb,
                latitude = latLong?.first,
                longitude = latLong?.second
            )
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun extractLocationName(lat: Double, lon: Double): String? = null
}
