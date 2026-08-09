package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PersonMergeLogDao {

    @Insert
    suspend fun insert(event: PersonMergeLogEntity): Long

    /** All events in order. Reducers must read everything to rebuild state reliably. */
    @Query("SELECT * FROM person_merge_log ORDER BY id ASC")
    suspend fun allOldestFirst(): List<PersonMergeLogEntity>

    /** The last N events for a debug/undo panel (newest first). */
    @Query("SELECT * FROM person_merge_log ORDER BY id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<PersonMergeLogEntity>

    /** All events of a given kind, oldest first. */
    @Query("SELECT * FROM person_merge_log WHERE eventKind = :kind ORDER BY id ASC")
    suspend fun ofKind(kind: Int): List<PersonMergeLogEntity>

    /** Lookup an event by row id. */
    @Query("SELECT * FROM person_merge_log WHERE id = :id")
    suspend fun byId(id: Long): PersonMergeLogEntity?

    /** Total event count (UI badge / "Phase 3 data exists" check). */
    @Query("SELECT COUNT(*) FROM person_merge_log")
    suspend fun count(): Int

    /** A model replacement starts a new embedding space, so old derived suggestions are invalid. */
    @Query("DELETE FROM person_merge_log")
    suspend fun deleteAll()

    /** All events ≥ sinceTs and originating from the system, used by dedupe suggestions today. */
    @Query(
        "SELECT * FROM person_merge_log WHERE origin = 'system' AND createdAt >= :sinceTs ORDER BY id ASC"
    )
    suspend fun systemSuggestionsSince(sinceTs: Long): List<PersonMergeLogEntity>
}
