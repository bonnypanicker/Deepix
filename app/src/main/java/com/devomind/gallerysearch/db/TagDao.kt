package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface TagDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: TagEntity): Long

    @Query("SELECT * FROM tags ORDER BY name ASC")
    suspend fun getAll(): List<TagEntity>

    @Query("SELECT * FROM tags WHERE id = :id")
    suspend fun getById(id: Long): TagEntity?

    @Query("DELETE FROM tags WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM media_tag_cross_ref WHERE mediaUri = :mediaUri")
    suspend fun clearTagsForMedia(mediaUri: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addMediaTagCrossRef(crossRef: MediaTagCrossRef)

    @Transaction
    @Query(
        """
        SELECT tags.* FROM tags
        INNER JOIN media_tag_cross_ref ON tags.id = media_tag_cross_ref.tagId
        WHERE media_tag_cross_ref.mediaUri = :mediaUri
        ORDER BY tags.name ASC
        """
    )
    suspend fun getTagsForMedia(mediaUri: String): List<TagEntity>

    @Transaction
    @Query(
        """
        SELECT media_metadata.uri FROM media_metadata
        INNER JOIN media_tag_cross_ref ON media_metadata.uri = media_tag_cross_ref.mediaUri
        WHERE media_tag_cross_ref.tagId = :tagId
        """
    )
    suspend fun getMediaUrisForTag(tagId: Long): List<String>

    @Query("SELECT * FROM tags WHERE name = :name LIMIT 1")
    suspend fun findByName(name: String): TagEntity?
}
