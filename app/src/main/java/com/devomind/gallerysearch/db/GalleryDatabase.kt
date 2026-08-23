package com.devomind.gallerysearch.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        MediaMetadataEntity::class,
        ExifMetadataEntity::class,
        FavoriteEntity::class,
        TagEntity::class,
        MediaTagCrossRef::class,
        PersonPhotoEntity::class,
        PersonEntity::class,
        FaceEntity::class,
        PersonMergeLogEntity::class,
        RecentSearchEntity::class
    ],
    version = 8,
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
    abstract fun personMergeLogDao(): PersonMergeLogDao
    abstract fun recentSearchDao(): RecentSearchDao

    companion object {
        private const val DATABASE_NAME = "gallery_metadata.db"

        /**
         * v3 → v4: Phase 2 widened person_photos (phash→dhash, status lifecycle, burst-exemplar
         * columns, capturedAt). The table is a recomputable pipeline cache, so drop/recreate is
         * safe — a targeted migration avoids the destructive fallback wiping user data
         * (favorites, tags, media metadata) that lives in the same database.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS `person_photos`")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `person_photos` (
                        `uri` TEXT NOT NULL,
                        `dhash` INTEGER NOT NULL,
                        `clipPersonScore` REAL NOT NULL,
                        `lastAnalyzedAt` INTEGER NOT NULL,
                        `status` TEXT NOT NULL,
                        `faceCount` INTEGER NOT NULL,
                        `exemplarQuality` REAL NOT NULL,
                        `exemplarPhotoUri` TEXT,
                        `capturedAt` INTEGER NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`uri`)
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_photos_status` ON `person_photos` (`status`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_photos_lastAnalyzedAt` ON `person_photos` (`lastAnalyzedAt`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_photos_capturedAt` ON `person_photos` (`capturedAt`)")
            }
        }

        /**
         * v4 → v5: Phase 3 adds person_merge_log (append-only audit trail).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `person_merge_log` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `eventKind` INTEGER NOT NULL,
                        `personId` INTEGER NOT NULL,
                        `otherPersonId` INTEGER NOT NULL,
                        `metricJson` TEXT,
                        `refEventId` INTEGER NOT NULL,
                        `origin` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL
                    )
                    """.trimIndent()
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_merge_log_eventKind` ON `person_merge_log` (`eventKind`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_merge_log_refEventId` ON `person_merge_log` (`refEventId`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_person_merge_log_personId` ON `person_merge_log` (`personId`)")
            }
        }

        /**
         * v5 → v6: adds recent_searches (search history for the pre-query empty state). New table,
         * no existing data touched.
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `recent_searches` (
                        `query` TEXT NOT NULL,
                        `searchedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`query`)
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * v6 → v7: adds persons.relationship (person identity: name + relationship chips). New
         * nullable column, no existing rows touched.
         */
        private val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `persons` ADD COLUMN `relationship` TEXT DEFAULT NULL")
            }
        }

        /**
         * v7 → v8: adds faces.rotationDegrees — the clockwise quarter-turn (0/90/180/270) the
         * face was accepted in under the rotation-retry policy. Cover rendering rotates the
         * crop by this so sideways faces show upright. Existing faces were all detected
         * upright → 0.
         */
        private val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `faces` ADD COLUMN `rotationDegrees` INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: GalleryDatabase? = null

        fun getInstance(context: Context): GalleryDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    GalleryDatabase::class.java,
                    DATABASE_NAME
                )
                    .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { instance = it }
            }
        }
    }
}
