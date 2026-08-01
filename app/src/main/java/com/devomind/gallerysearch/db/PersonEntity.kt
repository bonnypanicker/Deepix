package com.devomind.gallerysearch.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A recognized person cluster in the People Album. Phase 1 only defines the table — clustering
 * lands in Phase 3.
 *
 * @param nameLabel user-assigned name; null until the user renames.
 * @param exemplarFaceId id of the FaceEntity used as this person's cover/exemplar.
 * @param isHidden whether the user archived/hid this person.
 */
@Entity(tableName = "persons")
data class PersonEntity(
    @PrimaryKey(autoGenerate = true) val personId: Long = 0,
    val nameLabel: String? = null,
    val exemplarFaceId: Long = 0,
    val isHidden: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
