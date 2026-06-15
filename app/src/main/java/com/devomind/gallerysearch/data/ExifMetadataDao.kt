package com.devomind.gallerysearch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExifMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(exif: ExifMetadata)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(exifList: List<ExifMetadata>)

    @Query("SELECT * FROM exif_metadata WHERE uri = :uri")
    suspend fun getByUri(uri: String): ExifMetadata?

    @Query("DELETE FROM exif_metadata")
    suspend fun clearAll()
}
