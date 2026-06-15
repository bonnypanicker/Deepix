package com.devomind.gallerysearch.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media_metadata")
data class MediaMetadata(
    @PrimaryKey val uri: String,
    val bucketId: String,
    val bucketName: String,
    val displayName: String,
    val dateTaken: Long,
    val dateAdded: Long,
    val width: Int,
    val height: Int,
    val sizeBytes: Long,
    val mimeType: String,
    val mediaType: String,
    val relativePath: String?,
    val duration: Long = 0,
    val indexed: Boolean = false
)
