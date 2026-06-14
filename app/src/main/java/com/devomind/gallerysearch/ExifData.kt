package com.devomind.gallerysearch

data class ExifData(
    val make: String? = null,
    val model: String? = null,
    val lensModel: String? = null,
    val fNumber: Double? = null,
    val exposureTime: Double? = null,
    val iso: Int? = null,
    val focalLength: Double? = null,
    val flash: Int? = null,
    val whiteBalance: Int? = null,
    val gpsLatitude: Double? = null,
    val gpsLongitude: Double? = null,
    val gpsAltitude: Double? = null,
    val dateTimeOriginal: Long? = null
) {
    val hasCameraInfo: Boolean
        get() = make != null || model != null || lensModel != null ||
            fNumber != null || exposureTime != null || iso != null || focalLength != null

    val hasGps: Boolean
        get() = gpsLatitude != null && gpsLongitude != null
}
