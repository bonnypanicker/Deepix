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

    /** Lightweight detail-page query: avoids loading every stored embedding just to open a person. */
    @Query("SELECT DISTINCT photoUri FROM faces WHERE personId = :personId")
    suspend fun distinctPhotoUrisByPerson(personId: Long): List<String>

    @Query("SELECT COUNT(*) FROM faces WHERE personId = :personId")
    suspend fun countByPerson(personId: Long): Int

    @Query("SELECT * FROM faces WHERE faceId = :faceId")
    suspend fun findById(faceId: Long): FaceEntity?

    @Query("UPDATE faces SET isExemplar = :isExemplar WHERE faceId = :faceId")
    suspend fun setExemplar(faceId: Long, isExemplar: Boolean)

    /** All faces that have an embedding — used by Phase 2 exemplar-vote clustering. */
    @Query("SELECT * FROM faces WHERE embeddingJson IS NOT NULL")
    suspend fun findAllWithEmbeddings(): List<FaceEntity>

    @Query("SELECT COUNT(*) FROM faces")
    suspend fun countAll(): Int

    /** A single good persisted face is enough for the virtual People collection cover. */
    @Query("SELECT photoUri FROM faces WHERE embeddingJson IS NOT NULL ORDER BY qualityScore DESC, faceId DESC LIMIT 1")
    suspend fun bestRecognizedFacePhotoUri(): String?

    @Query("SELECT COUNT(*) FROM faces WHERE embeddingJson IS NOT NULL AND embeddingModelVersion != :modelVersion")
    suspend fun countWithDifferentEmbeddingModel(modelVersion: String): Int

    /** Move all faces of a person to another. Used by merge. */
    @Query("UPDATE faces SET personId = :toPersonId WHERE personId = :fromPersonId")
    suspend fun reassignPerson(fromPersonId: Long, toPersonId: Long)

    /** Move a hand-picked list of face ids to a specific person. Used by split. */
    @Query("UPDATE faces SET personId = :toPersonId WHERE faceId IN (:faceIds)")
    suspend fun reassignFaces(faceIds: List<Long>, toPersonId: Long)

    @Query("SELECT COUNT(*) FROM faces WHERE personId IS NULL")
    suspend fun countUnassigned(): Int

    @Query("DELETE FROM faces WHERE photoUri = :uri")
    suspend fun deleteByPhoto(uri: String)

    @Query("DELETE FROM faces")
    suspend fun deleteAll()
}
