package com.devomind.gallerysearch.data
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
@Dao
interface MediaTagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(mediaTag: MediaTagCrossRef)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(mediaTags: List<MediaTagCrossRef>)
    @Query("DELETE FROM media_tags WHERE uri = :uri AND tagName = :tagName")
    suspend fun delete(uri: String, tagName: String)
    @Query("DELETE FROM media_tags WHERE uri = :uri")
    suspend fun deleteAllForMedia(uri: String)
    @Query("DELETE FROM media_tags WHERE tagName = :tagName")
    suspend fun deleteAllForTag(tagName: String)
    @Query("SELECT tagName FROM media_tags WHERE uri = :uri")
    suspend fun getTagsForMedia(uri: String): List<String>
    @Query("SELECT uri FROM media_tags WHERE tagName = :tagName")
    suspend fun getMediaForTag(tagName: String): List<String>
    @Query("SELECT EXISTS(SELECT 1 FROM media_tags WHERE uri = :uri AND tagName = :tagName)")
    suspend fun isTagged(uri: String, tagName: String): Boolean
    @Query("SELECT DISTINCT tagName FROM media_tags")
    suspend fun getAllTags(): List<String>
}
