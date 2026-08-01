package com.devomind.gallerysearch.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        MediaMetadataEntity::class,
        ExifMetadataEntity::class,
        FavoriteEntity::class,
        TagEntity::class,
        MediaTagCrossRef::class,
        PersonPhotoEntity::class,
        PersonEntity::class,
        FaceEntity::class
    ],
    version = 3,
    exportSchema = false
)
abstract class GalleryDatabase : RoomDatabase() {

    abstract fun mediaMetadataDao(): MediaMetadataDao
    abstract fun exifMetadataDao(): ExifMetadataDao
    abstract fun favoriteDao(): FavoriteDao
    abstract fun tagDao(): TagDao
    abstract fun personPhotoDao(): PersonPhotoDao
    abstract fun personDao(): PersonDao
    abstract fun faceDao(): FaceDao

    companion object {
        private const val DATABASE_NAME = "gallery_metadata.db"

        @Volatile
        private var instance: GalleryDatabase? = null

        fun getInstance(context: Context): GalleryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GalleryDatabase::class.java,
                    DATABASE_NAME
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
