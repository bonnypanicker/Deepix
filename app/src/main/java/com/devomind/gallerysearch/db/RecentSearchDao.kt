package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface RecentSearchDao {

    /** Insert or bump (REPLACE refreshes searchedAt, moving the query to the top). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: RecentSearchEntity)

    @Query("SELECT `query` FROM recent_searches ORDER BY searchedAt DESC LIMIT :limit")
    suspend fun recentQueries(limit: Int): List<String>

    @Query("DELETE FROM recent_searches WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM recent_searches")
    suspend fun clearAll()

    /** Keeps the table bounded; rows past the newest [keep] entries are dropped. */
    @Query(
        "DELETE FROM recent_searches WHERE `query` NOT IN " +
            "(SELECT `query` FROM recent_searches ORDER BY searchedAt DESC LIMIT :keep)"
    )
    suspend fun prune(keep: Int)
}
