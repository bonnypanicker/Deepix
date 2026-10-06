package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface PersonDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(person: PersonEntity): Long

    @Update
    suspend fun update(person: PersonEntity)

    @Query("SELECT * FROM persons WHERE personId = :id")
    suspend fun findById(id: Long): PersonEntity?

    @Query("SELECT * FROM persons WHERE NOT isHidden")
    suspend fun allVisible(): List<PersonEntity>

    /** All persons, including hidden — used by matching so we don't create duplicates. */
    @Query("SELECT * FROM persons")
    suspend fun all(): List<PersonEntity>

    @Query("UPDATE persons SET nameLabel = :name, relationship = :relationship, updatedAt = :now WHERE personId = :id")
    suspend fun updateIdentity(id: Long, name: String?, relationship: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE persons SET isHidden = 1, updatedAt = :now WHERE personId = :id")
    suspend fun hide(id: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM persons")
    suspend fun deleteAll()

    @Query(
        """
        UPDATE persons SET exemplarFaceId = :faceId, updatedAt = :now
        WHERE personId = :id
        """
    )
    suspend fun setExemplarFace(id: Long, faceId: Long, now: Long = System.currentTimeMillis())

    /**
     * Drops the cover pointer for every person whose exemplar face is about to disappear, back to the
     * 0 = "not chosen yet" state a new person starts in. Run before the faces are deleted so the
     * person pages never resolve a missing row.
     */
    @Query(
        """
        UPDATE persons SET exemplarFaceId = 0, updatedAt = :now
        WHERE exemplarFaceId IN (:faceIds)
        """
    )
    suspend fun clearExemplarFaces(faceIds: List<Long>, now: Long = System.currentTimeMillis())
}
