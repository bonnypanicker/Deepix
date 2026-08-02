package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FaceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(face: FaceEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(faces: List<FaceEntity>)

    @Query("SELECT * FROM faces WHERE photoUri = :uri ORDER BY qualityScore DESC")
    suspend fun findByPhoto(uri: String): List<FaceEntity>

    @Query("SELECT * FROM faces WHERE personId = :personId ORDER BY qualityScore DESC")
    suspend fun findByPerson(personId: Long): List<FaceEntity>

    @Query("UPDATE faces SET isExemplar = :isExemplar WHERE faceId = :faceId")
    suspend fun setExemplar(faceId: Long, isExemplar: Boolean)

    /** All faces that have an embedding — used by Phase 2 exemplar-vote clustering. */
    @Query("SELECT * FROM faces WHERE embeddingJson IS NOT NULL")
    suspend fun findAllWithEmbeddings(): List<FaceEntity>

    @Query("SELECT COUNT(*) FROM faces")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM faces WHERE personId IS NULL")
    suspend fun countUnassigned(): Int

    @Query("DELETE FROM faces WHERE photoUri = :uri")
    suspend fun deleteByPhoto(uri: String)

    @Query("DELETE FROM faces")
    suspend fun deleteAll()
}
