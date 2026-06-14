package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ExifMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(exif: ExifMetadataEntity)

    @Query("SELECT * FROM exif_metadata WHERE uri = :uri")
    suspend fun getByUri(uri: String): ExifMetadataEntity?
}
