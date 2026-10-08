package com.devomind.gallerysearch

import android.content.Context
import com.devomind.gallerysearch.db.EmbeddingSourceEntity
import com.devomind.gallerysearch.db.ExifMetadataEntity
import com.devomind.gallerysearch.db.FaceEntity
import com.devomind.gallerysearch.db.FavoriteEntity
import com.devomind.gallerysearch.db.GalleryDatabase
import com.devomind.gallerysearch.db.GpsPoint
import com.devomind.gallerysearch.db.MediaMetadataEntity
import com.devomind.gallerysearch.db.MediaTagCrossRef
import com.devomind.gallerysearch.db.PersonEntity
import com.devomind.gallerysearch.db.RecentSearchEntity
import com.devomind.gallerysearch.db.RoomBatching
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
    private val personPhotoDao = database.personPhotoDao()
    private val recentSearchDao = database.recentSearchDao()
    private val embeddingSourceDao = database.embeddingSourceDao()

    suspend fun upsertMedia(items: List<GalleryRepository.MediaItem>) {
        withContext(Dispatchers.IO) {
            val entities = items.map { it.toEntity() }
            // A pass ends by writing the whole library, which is far past what one INSERT can bind.
            RoomBatching.chunks(entities, MediaMetadataEntity.BindVariables)
                .forEach { mediaDao.upsert(it) }
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

    /**
     * EXIF rows for a set of uris. The callers hand over the whole search pool, so this reads in
     * statements of 900 uris rather than one query per photo.
     */
    suspend fun getExifForUris(uris: List<String>): Map<String, ExifData> {
        if (uris.isEmpty()) return emptyMap()
        return withContext(Dispatchers.IO) {
            RoomBatching.chunks(uris.distinct())
                .flatMap { exifDao.getByUris(it) }
                .associate { it.uri to it.toData() }
        }
    }

    suspend fun recognizedPeopleForPhotoUris(uris: List<String>): SearchPeopleMatch {
        if (uris.isEmpty()) return SearchPeopleMatch(emptySet(), emptySet())
        return withContext(Dispatchers.IO) {
            RoomBatching.chunks(uris.distinct()).fold(SearchPeopleMatch(emptySet(), emptySet())) { match, chunk ->
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
            RoomBatching.chunks(uris.distinct())
                .flatMapTo(LinkedHashSet()) { exifDao.photoUrisWithLocation(it) }
        }
    }

    /** URIs that already have an exif_metadata row (GPS or not) — bounds progressive EXIF reads. */
    suspend fun existingExifUris(uris: List<String>): Set<String> {
        if (uris.isEmpty()) return emptySet()
        return withContext(Dispatchers.IO) {
            RoomBatching.chunks(uris.distinct())
                .flatMapTo(HashSet()) { exifDao.existingUris(it) }
        }
    }

    /** Every stored GPS fix (photos that were EXIF-read and have coordinates). */
    suspend fun gpsPoints(): List<GpsPoint> = withContext(Dispatchers.IO) { exifDao.gpsPoints() }

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

    /**
     * What each stored CLIP embedding was encoded from, keyed by URI. Read once per indexing pass —
     * one small row per indexed photo, and the pass needs all of them to spot the changed few.
     */
    suspend fun embeddingSignatures(): Map<String, MediaSignature> = withContext(Dispatchers.IO) {
        embeddingSourceDao.getAll().associate { it.uri to it.toSignature() }
    }

    /** Records the file state behind freshly encoded embeddings, so a later edit of these files is detectable. */
    suspend fun recordEmbeddingSources(items: List<GalleryRepository.MediaItem>) {
        if (items.isEmpty()) return
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // The v8→v9 backfill signs the whole library in one callback, six columns at a time.
            RoomBatching.chunks(items.map { it.toEmbeddingSourceEntity(now) }, EmbeddingSourceEntity.BindVariables)
                .forEach { embeddingSourceDao.upsert(it) }
        }
    }

    /**
     * Everything the app must forget when an editor replaces a photo's bytes under its original URI.
     *
     * The CLIP side is a marker rather than a deletion: the stored vector still describes the same
     * scene, and removing it would make the photo unfindable until a pass re-encodes it — which is
     * exactly what the marker schedules. The face side is a real deletion, because re-detection inserts
     * new rows and the pre-edit face would otherwise sit in the cluster beside the new one.
     */
    suspend fun invalidatePhotoForReanalysis(uri: String) {
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val stale = EmbeddingFreshness.StaleSignature
            embeddingSourceDao.upsert(
                EmbeddingSourceEntity(
                    uri = uri,
                    dateModifiedMs = stale.dateModifiedMillis,
                    sizeBytes = stale.sizeBytes,
                    width = stale.width,
                    height = stale.height,
                    recordedAt = now
                )
            )
            personPhotoDao.invalidateForReanalysis(uri)
            val removedFaceIds = faceDao.idsForPhoto(uri)
            if (removedFaceIds.isNotEmpty()) {
                faceDao.deleteByPhoto(uri)
                // A person whose cover was one of these faces falls back to exemplarFaceId = 0 — the
                // state a freshly created person already starts in — until re-analysis replaces them.
                RoomBatching.chunks(removedFaceIds)
                    .forEach { personDao.clearExemplarFaces(it, now) }
            }
        }
    }

    private fun EmbeddingSourceEntity.toSignature(): MediaSignature =
        MediaSignature(dateModifiedMs, sizeBytes, width, height)

    private fun GalleryRepository.MediaItem.toEmbeddingSourceEntity(recordedAt: Long): EmbeddingSourceEntity {
        val signature = embeddingSignature()
        return EmbeddingSourceEntity(
            uri = uri.toString(),
            dateModifiedMs = signature.dateModifiedMillis,
            sizeBytes = signature.sizeBytes,
            width = signature.width,
            height = signature.height,
            recordedAt = recordedAt
        )
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
        const val MaxRecentSearches = 25
    }
}
