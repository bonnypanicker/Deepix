package com.devomind.gallerysearch.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * One row per photo the [com.devomind.gallerysearch.FaceScanWorker]-style pass visits — tracks
 * whether it has been anayzed, a placeholder clip-person score (Phase 2 CLIP gate), and its phash
 * for the duplicate-guard. The uri is the MediaStore content URI.
 */
@Entity(tableName = "person_photos")
data class PersonPhotoEntity(
    @PrimaryKey val uri: String,
    val phash: Long = 0L,
    val clipPersonScore: Float = 0f,
    val lastAnalyzedAt: Long = 0L
)
