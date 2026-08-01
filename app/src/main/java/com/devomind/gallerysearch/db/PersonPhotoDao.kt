package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PersonPhotoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: PersonPhotoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(photos: List<PersonPhotoEntity>)

    @Query("SELECT * FROM person_photos WHERE uri = :uri")
    suspend fun findByUri(uri: String): PersonPhotoEntity?

    @Query("SELECT COUNT(*) FROM person_photos")
    suspend fun countAll(): Int

    @Query("DELETE FROM person_photos WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
