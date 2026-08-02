package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PersonPhotoDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(photo: PersonPhotoEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(photos: List<PersonPhotoEntity>)

    @Update
    suspend fun update(photo: PersonPhotoEntity)

    @Query("SELECT * FROM person_photos WHERE uri = :uri")
    suspend fun findByUri(uri: String): PersonPhotoEntity?

    /** Rows awaiting work, ordered by recency. */
    @Query("SELECT * FROM person_photos WHERE status = :status ORDER BY lastAnalyzedAt DESC LIMIT :limit")
    suspend fun findByStatus(status: String, limit: Int): List<PersonPhotoEntity>

    /** Photos sharing a burst signature — used by DuplicateGuard to find near-duplicate frames.
     *  Keys off [PersonPhotoEntity.capturedAt] (capture timestamp) rather than `createdAt` because
     *  the first backfill creates every row in seconds — which would otherwise make the whole
     *  library look like one giant burst. */
    @Query(
        """
        SELECT * FROM person_photos
        WHERE dhash != 0
          AND capturedAt BETWEEN (:capturedAtMillis - :burstWindowMillis) AND (:capturedAtMillis + :burstWindowMillis)
        ORDER BY capturedAt ASC
        """
    )
    suspend fun findBurstCandidates(capturedAtMillis: Long, burstWindowMillis: Long): List<PersonPhotoEntity>

    /** Latest exemplar photo (highest quality) for the burst anchored at this exemplar URI. */
    @Query(
        """
        SELECT * FROM person_photos
        WHERE exemplarPhotoUri = :exemplarUri
          OR uri = :exemplarUri
        ORDER BY exemplarQuality DESC
        LIMIT 1
        """
    )
    suspend fun findCurrentBurstExemplar(exemplarUri: String): PersonPhotoEntity?

    @Query("UPDATE person_photos SET exemplarPhotoUri = :exemplarUri WHERE uri IN (:memberUris)")
    suspend fun setBurstExemplar(memberUris: List<String>, exemplarUri: String)

    @Query("SELECT COUNT(*) FROM person_photos")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM person_photos WHERE status = :status")
    suspend fun countByStatus(status: String): Int

    @Query("SELECT AVG(clipPersonScore) FROM person_photos WHERE status = :status")
    suspend fun averGateScoreByStatus(status: String): Double?

    @Query("DELETE FROM person_photos WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)

    /** True when [uri] has been through the face pipeline already (any terminal status). */
    @Query(
        "SELECT EXISTS(SELECT 1 FROM person_photos WHERE uri = :uri AND status IN (:terminalStatuses))"
    )
    suspend fun isProcessed(uri: String, terminalStatuses: List<String> = Companion.TerminalStatuses): Boolean

    companion object {
        /** Stable terminal values — avoid marking work done repeatedly. */
        val TerminalStatuses: List<String> = listOf(
            PersonPhotoEntity.Status.RESOLVED_TO_PERSON,
            PersonPhotoEntity.Status.GATED_NO_FACES,
            PersonPhotoEntity.Status.DUPLICATE_IN_BURST,
            PersonPhotoEntity.Status.NO_FACES,
            PersonPhotoEntity.Status.DECODE_FAILED
        )
    }
}
