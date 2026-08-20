package com.devomind.gallerysearch.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One entry of the search-history list powering the search page's pre-query empty state. */
@Entity(tableName = "recent_searches")
data class RecentSearchEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long
)
