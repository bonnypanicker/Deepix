package com.devomind.gallerysearch.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "favorites")
data class FavoriteEntity(
    @PrimaryKey
    val uri: String,
    val favoritedAt: Long
)
