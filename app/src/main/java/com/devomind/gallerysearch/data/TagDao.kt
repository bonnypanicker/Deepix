package com.devomind.gallerysearch.data
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
@Dao
interface TagDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: Tag)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<Tag>)
    @Query("SELECT * FROM tags ORDER BY name")
    suspend fun getAll(): List<Tag>
    @Query("DELETE FROM tags WHERE name = :name")
    suspend fun delete(name: String)
}
