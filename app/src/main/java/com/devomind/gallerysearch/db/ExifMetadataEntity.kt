package com.devomind.gallerysearch.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exif_metadata")
data class ExifMetadataEntity(
    @PrimaryKey
    val uri: String,
    val make: String?,
    val model: String?,
    val lensModel: String?,
    val fNumber: Double?,
    val exposureTime: Double?,
    val iso: Int?,
    val focalLength: Double?,
    val flash: Int?,
    val whiteBalance: Int?,
    val gpsLatitude: Double?,
    val gpsLongitude: Double?,
    val gpsAltitude: Double?,
    val dateTimeOriginal: Long?,
    val capturedAt: Long?
)
