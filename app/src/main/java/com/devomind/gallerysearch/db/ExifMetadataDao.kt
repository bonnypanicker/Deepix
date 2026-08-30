package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** A photo's GPS fix from the exif_metadata table (Room query POJO). */
data class GpsPoint(val uri: String, val lat: Double, val lng: Double)

@Dao
interface ExifMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exif: ExifMetadataEntity)

    @Query("SELECT * FROM exif_metadata WHERE uri = :uri")
    suspend fun getByUri(uri: String): ExifMetadataEntity?

    @Query("SELECT uri FROM exif_metadata WHERE uri IN (:uris) AND gpsLatitude IS NOT NULL AND gpsLongitude IS NOT NULL")
    suspend fun photoUrisWithLocation(uris: List<String>): List<String>

    @Query("SELECT uri FROM exif_metadata WHERE uri IN (:uris)")
    suspend fun existingUris(uris: List<String>): List<String>

    @Query("SELECT uri, gpsLatitude AS lat, gpsLongitude AS lng FROM exif_metadata WHERE gpsLatitude IS NOT NULL AND gpsLongitude IS NOT NULL")
    suspend fun gpsPoints(): List<GpsPoint>
}
