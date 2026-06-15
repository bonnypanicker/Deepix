package com.devomind.gallerysearch.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface MediaMetadataDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(metadata: MediaMetadata)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(metadata: List<MediaMetadata>)

    @Query("SELECT * FROM media_metadata WHERE uri = :uri")
    suspend fun getByUri(uri: String): MediaMetadata?

    @Query("DELETE FROM media_metadata")
    suspend fun clearAll()

    @Query("SELECT * FROM media_metadata")
    fun observeAll(): Flow<List<MediaMetadata>>
}
