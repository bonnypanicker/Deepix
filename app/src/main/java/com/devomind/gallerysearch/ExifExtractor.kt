package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale

object ExifExtractor {

    private const val Tag = "ExifExtractor"

    fun extract(context: Context, uri: Uri): ExifData {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val exif = ExifInterface(stream)
                ExifData(
                    make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim()?.takeIf { it.isNotBlank() },
                    model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim()?.takeIf { it.isNotBlank() },
                    lensModel = readLensModel(exif),
                    fNumber = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER),
                    exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME),
                    iso = readIso(exif),
                    focalLength = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH),
                    flash = exif.getAttributeInt(ExifInterface.TAG_FLASH, 0),
                    whiteBalance = exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, 0),
                    gpsLatitude = readGpsCoordinate(exif, true),
                    gpsLongitude = readGpsCoordinate(exif, false),
                    gpsAltitude = exif.getAttributeDouble(ExifInterface.TAG_GPS_ALTITUDE),
                    dateTimeOriginal = readDateTimeOriginal(exif)
                )
            } ?: ExifData()
        }.onFailure { error ->
            Log.w(Tag, "Failed to extract EXIF for $uri", error)
        }.getOrDefault(ExifData())
    }

    private fun readLensModel(exif: ExifInterface): String? {
        val lensModel = exif.getAttribute(ExifInterface.TAG_LENS_MODEL)
        if (!lensModel.isNullOrBlank()) return lensModel.trim()

        val lensSpec = exif.getAttribute(ExifInterface.TAG_LENS_SPECIFICATION)
        if (!lensSpec.isNullOrBlank()) return lensSpec.trim()

        return null
    }

    private fun readIso(exif: ExifInterface): Int? {
        val iso = exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0)
        if (iso > 0) return iso

        val isoSpeed = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
        return if (isoSpeed > 0) isoSpeed else null
    }

    private fun readGpsCoordinate(exif: ExifInterface, latitude: Boolean): Double? {
        val latLong = FloatArray(2)
        if (!exif.getLatLong(latLong)) return null
        return if (latitude) latLong[0].toDouble() else latLong[1].toDouble()
    }

    private fun readDateTimeOriginal(exif: ExifInterface): Long? {
        val value = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
            ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            ?: return null

        return runCatching {
            SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(value)?.time
        }.getOrNull()
    }

    private fun ExifInterface.getAttributeDouble(tag: String): Double? {
        val value = getAttribute(tag) ?: return null
        return value.toDoubleOrNull()?.takeIf { it > 0 }
    }
}
