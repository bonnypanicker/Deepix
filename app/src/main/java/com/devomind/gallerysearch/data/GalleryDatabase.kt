package com.devomind.gallerysearch.data
import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
@Database(
    entities = [MediaMetadata::class, ExifMetadata::class, Favorite::class, Tag::class, MediaTagCrossRef::class],
    version = 1,
    exportSchema = false
)
abstract class GalleryDatabase : RoomDatabase() {
    abstract fun mediaMetadataDao(): MediaMetadataDao
    abstract fun exifMetadataDao(): ExifMetadataDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun tagDao(): TagDao
    abstract fun mediaTagDao(): MediaTagDao

    companion object {
        @Volatile private var INSTANCE: GalleryDatabase? = null

        fun get(context: Context): GalleryDatabase = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Room.databaseBuilder(context.applicationContext, GalleryDatabase::class.java, "gallery.db")
                .fallbackToDestructiveMigration()
                .build()
                .also { INSTANCE = it }
        }
    }
}
