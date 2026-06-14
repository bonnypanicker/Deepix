package com.devomind.gallerysearch.db

import androidx.room.Entity

@Entity(
    tableName = "media_tag_cross_ref",
    primaryKeys = ["mediaUri", "tagId"]
)
data class MediaTagCrossRef(
    val mediaUri: String,
    val tagId: Long
)
