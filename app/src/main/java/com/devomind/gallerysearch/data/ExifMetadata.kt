package com.devomind.gallerysearch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "exif_metadata")
data class ExifMetadata(
    @PrimaryKey val uri: String,
    val cameraModel: String? = null,
    val aperture: String? = null,
    val iso: String? = null,
    val shutterSpeed: String? = null,
    val focalLength: String? = null,
    val whiteBalance: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationName: String? = null
)
