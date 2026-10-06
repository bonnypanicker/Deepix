package com.devomind.gallerysearch.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface EmbeddingSourceDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(items: List<EmbeddingSourceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: EmbeddingSourceEntity)

    /** Every recorded source. Read once per indexing pass; one small row per indexed photo. */
    @Query("SELECT * FROM embedding_source")
    suspend fun getAll(): List<EmbeddingSourceEntity>

    @Query("SELECT * FROM embedding_source WHERE uri = :uri")
    suspend fun getByUri(uri: String): EmbeddingSourceEntity?

    /** Drops the record so the next pass treats the photo's embedding as missing its signature. */
    @Query("DELETE FROM embedding_source WHERE uri = :uri")
    suspend fun deleteByUri(uri: String)
}
