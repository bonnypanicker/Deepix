package com.devomind.gallerysearch.data

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "media_tags",
    primaryKeys = ["uri", "tagName"],
    indices = [Index("tagName")]
)
data class MediaTagCrossRef(
    val uri: String,
    val tagName: String
)
