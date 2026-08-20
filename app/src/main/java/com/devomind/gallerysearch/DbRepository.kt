package com.devomind.gallerysearch

import android.content.Context
import com.devomind.gallerysearch.db.ExifMetadataEntity
import com.devomind.gallerysearch.db.FaceEntity
import com.devomind.gallerysearch.db.FavoriteEntity
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.MediaMetadataEntity
import com.devomind.gallerysearch.db.MediaTagCrossRef
import com.devomind.gallerysearch.db.PersonEntity
import com.devomind.gallerysearch.db.RecentSearchEntity
import com.devomind.gallerysearch.db.TagEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class SearchPeopleMatch(
    val personIds: Set<Long>,
    val photoUris: Set<String>
)

class DbRepository(context: Context) {

    private val database = GalleryDatabase.getInstance(context)
    private val mediaDao = database.mediaMetadataDao()
    private val exifDao = database.exifMetadataDao()
    private val favoriteDao = database.favoriteDao()
    private val tagDao = database.tagDao()
    private val faceDao = database.faceDao()
    private val personDao = database.personDao()
    private val recentSearchDao = database.recentSearchDao()

    suspend fun upsertMedia(items: List<GalleryRepository.MediaItem>) {
        withContext(Dispatchers.IO) {
            val entities = items.map { it.toEntity() }
            mediaDao.upsert(entities)
        }
    }

    suspend fun upsertMedia(item: GalleryRepository.MediaItem) {
        withContext(Dispatchers.IO) {
            mediaDao.upsert(item.toEntity())
        }
    }

    suspend fun getFavorites(): Set<String> {
        return withContext(Dispatchers.IO) {
            favoriteDao.getAllUris().toSet()
        }
    }

    suspend fun isFavorite(uri: String): Boolean {
        return withContext(Dispatchers.IO) {
            favoriteDao.isFavorite(uri)
        }
    }

    suspend fun toggleFavorite(uri: String): Boolean {
        return withContext(Dispatchers.IO) {
            if (favoriteDao.isFavorite(uri)) {
                favoriteDao.delete(uri)
                false
            } else {
                favoriteDao.insert(FavoriteEntity(uri, System.currentTimeMillis()))
                true
            }
        }
    }

    suspend fun insertFavorite(uri: String) {
        withContext(Dispatchers.IO) {
            favoriteDao.insert(FavoriteEntity(uri, System.currentTimeMillis()))
        }
    }

    suspend fun upsertExif(uri: String, exif: ExifData) {
        withContext(Dispatchers.IO) {
            exifDao.upsert(
                ExifMetadataEntity(
                    uri = uri,
                    make = exif.make,
                    model = exif.model,
                    lensModel = exif.lensModel,
                    fNumber = exif.fNumber,
                    exposureTime = exif.exposureTime,
                    iso = exif.iso,
                    focalLength = exif.focalLength,
                    flash = exif.flash,
                    whiteBalance = exif.whiteBalance,
                    gpsLatitude = exif.gpsLatitude,
                    gpsLongitude = exif.gpsLongitude,
                    gpsAltitude = exif.gpsAltitude,
                    dateTimeOriginal = exif.dateTimeOriginal,
                    capturedAt = exif.dateTimeOriginal
                )
            )
        }
    }

    suspend fun getExif(uri: String): ExifData? {
        return withContext(Dispatchers.IO) {
            exifDao.getByUri(uri)?.toData()
        }
    }

    suspend fun getExifForUris(uris: List<String>): Map<String, ExifData> {
        return withContext(Dispatchers.IO) {
            uris.mapNotNull { uri ->
                exifDao.getByUri(uri)?.toData()?.let { uri to it }
            }.toMap()
        }
    }

    suspend fun recognizedPeopleForPhotoUris(uris: List<String>): SearchPeopleMatch {
        if (uris.isEmpty()) return SearchPeopleMatch(emptySet(), emptySet())
        return withContext(Dispatchers.IO) {
            uris.distinct().chunked(RoomQueryChunkSize).fold(SearchPeopleMatch(emptySet(), emptySet())) { match, chunk ->
                SearchPeopleMatch(
                    personIds = match.personIds + faceDao.distinctPersonIdsForPhotos(chunk),
                    photoUris = match.photoUris + faceDao.recognizedPhotoUris(chunk)
                )
            }
        }
    }

    suspend fun photoUrisWithLocation(uris: List<String>): Set<String> {
        if (uris.isEmpty()) return emptySet()
        return withContext(Dispatchers.IO) {
            uris.distinct().chunked(RoomQueryChunkSize)
                .flatMapTo(LinkedHashSet()) { exifDao.photoUrisWithLocation(it) }
        }
    }

    suspend fun addTag(name: String, color: Int): Long {
        return withContext(Dispatchers.IO) {
            val existing = tagDao.findByName(name)
            if (existing != null) {
                existing.id
            } else {
                tagDao.insert(TagEntity(name = name, color = color, createdAt = System.currentTimeMillis()))
            }
        }
    }

    suspend fun getAllTags(): List<TagEntity> {
        return withContext(Dispatchers.IO) {
            tagDao.getAll()
        }
    }

    suspend fun getTagsForMedia(mediaUri: String): List<TagEntity> {
        return withContext(Dispatchers.IO) {
            tagDao.getTagsForMedia(mediaUri)
        }
    }

    suspend fun getMediaUrisForTag(tagId: Long): List<String> {
        return withContext(Dispatchers.IO) {
            tagDao.getMediaUrisForTag(tagId)
        }
    }

    suspend fun setTagsForMedia(mediaUri: String, tagIds: List<Long>) {
        withContext(Dispatchers.IO) {
            tagDao.clearTagsForMedia(mediaUri)
            tagIds.distinct().forEach { tagId ->
                tagDao.addMediaTagCrossRef(MediaTagCrossRef(mediaUri, tagId))
            }
        }
    }

    suspend fun deleteTag(tagId: Long) {
        return withContext(Dispatchers.IO) {
            tagDao.delete(tagId)
        }
    }

    /** All visible person clusters — used by the search page's "Search by person" row. */
    suspend fun visiblePeople(): List<PersonEntity> {
        return withContext(Dispatchers.IO) {
            personDao.allVisible()
        }
    }

    /** Full face rows for exemplar picking; call only for the handful of persons being rendered. */
    suspend fun facesForPerson(personId: Long): List<FaceEntity> {
        return withContext(Dispatchers.IO) {
            faceDao.findByPerson(personId)
        }
    }

    /** Distinct photo count for ranking clusters without loading embeddings. */
    suspend fun photoCountForPerson(personId: Long): Int {
        return withContext(Dispatchers.IO) {
            faceDao.distinctPhotoUrisByPerson(personId).size
        }
    }

    suspend fun recentSearches(limit: Int): List<String> {
        return withContext(Dispatchers.IO) {
            recentSearchDao.recentQueries(limit)
        }
    }

    /** Records/bumps a query and keeps the table bounded at [MaxRecentSearches]. */
    suspend fun recordRecentSearch(query: String) {
        withContext(Dispatchers.IO) {
            recentSearchDao.upsert(RecentSearchEntity(query, System.currentTimeMillis()))
            recentSearchDao.prune(MaxRecentSearches)
        }
    }

    suspend fun removeRecentSearch(query: String) {
        withContext(Dispatchers.IO) {
            recentSearchDao.delete(query)
        }
    }

    suspend fun clearRecentSearches() {
        withContext(Dispatchers.IO) {
            recentSearchDao.clearAll()
        }
    }

    private fun GalleryRepository.MediaItem.toEntity(): MediaMetadataEntity {
        return MediaMetadataEntity(
            uri = uri.toString(),
            bucketId = bucketId,
            bucketName = bucketName,
            displayName = displayName,
            mimeType = mimeType,
            dateTaken = dateMillis,
            dateAdded = dateMillis,
            width = width,
            height = height,
            duration = durationMillis,
            sizeBytes = 0L,
            orientation = when {
                width > height -> "landscape"
                height > width -> "portrait"
                width > 0 && width == height -> "square"
                else -> null
            },
            lastIndexedAt = System.currentTimeMillis()
        )
    }

    private fun ExifMetadataEntity.toData(): ExifData {
        return ExifData(
            make = make,
            model = model,
            lensModel = lensModel,
            fNumber = fNumber,
            exposureTime = exposureTime,
            iso = iso,
            focalLength = focalLength,
            flash = flash,
            whiteBalance = whiteBalance,
            gpsLatitude = gpsLatitude,
            gpsLongitude = gpsLongitude,
            gpsAltitude = gpsAltitude,
            dateTimeOriginal = dateTimeOriginal
        )
    }

    private companion object {
        const val RoomQueryChunkSize = 900
        const val MaxRecentSearches = 25
    }
}
