package com.devomind.gallerysearch.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_metadata")
data class MediaMetadataEntity(
    @PrimaryKey
    val uri: String,
    val bucketId: String,
    val bucketName: String,
    val displayName: String?,
    val mimeType: String?,
    val dateTaken: Long,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
    val duration: Long,
    val sizeBytes: Long,
    val orientation: String?,
    val lastIndexedAt: Long
)
