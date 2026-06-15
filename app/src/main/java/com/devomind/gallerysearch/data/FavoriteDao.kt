package com.devomind.gallerysearch.data
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(favorite: Favorite)
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(favorites: List<Favorite>)
    @Query("DELETE FROM favorites WHERE uri = :uri")
    suspend fun delete(uri: String)
    @Query("SELECT * FROM favorites WHERE uri = :uri")
    suspend fun getByUri(uri: String): Favorite?
    @Query("SELECT * FROM favorites")
    suspend fun getAll(): List<Favorite>
    @Query("SELECT COUNT(*) FROM favorites")
    suspend fun count(): Int
}
