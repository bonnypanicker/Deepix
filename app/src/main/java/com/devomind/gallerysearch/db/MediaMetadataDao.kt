package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MediaMetadataDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<MediaMetadataEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: MediaMetadataEntity)

    @Query("SELECT * FROM media_metadata WHERE uri = :uri")
    suspend fun getByUri(uri: String): MediaMetadataEntity?

    @Query("SELECT uri FROM media_metadata")
    suspend fun getAllUris(): List<String>

    @Query("SELECT * FROM media_metadata WHERE bucketId = :bucketId")
    suspend fun getByBucket(bucketId: String): List<MediaMetadataEntity>

    @Query("DELETE FROM media_metadata WHERE uri NOT IN (:uris)")
    suspend fun deleteNotIn(uris: List<String>)
}
