package com.devomind.gallerysearch
import android.content.Context
import com.devomind.gallerysearch.data.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class DbRepository(context: Context) {
    private val db = GalleryDatabase.get(context)
    private val mediaDao = db.mediaMetadataDao()
    private val exifDao = db.exifMetadataDao()
    private val favoriteDao = db.favoriteDao()
    private val tagDao = db.tagDao()
    private val mediaTagDao = db.mediaTagDao()

    suspend fun insertMediaMetadata(items: List<MediaMetadata>) = withContext(Dispatchers.IO) { mediaDao.insertAll(items) }
    suspend fun getMediaMetadata(uri: String): MediaMetadata? = withContext(Dispatchers.IO) { mediaDao.getByUri(uri) }
    suspend fun clearAllMedia() = withContext(Dispatchers.IO) { mediaDao.clearAll() }

    suspend fun insertExif(items: List<ExifMetadata>) = withContext(Dispatchers.IO) { exifDao.insertAll(items) }
    suspend fun getExif(uri: String): ExifMetadata? = withContext(Dispatchers.IO) { exifDao.getByUri(uri) }
    suspend fun clearExif() = withContext(Dispatchers.IO) { exifDao.clearAll() }

    suspend fun setFavorite(uri: String) = withContext(Dispatchers.IO) { favoriteDao.insert(Favorite(uri)) }
    suspend fun removeFavorite(uri: String) = withContext(Dispatchers.IO) { favoriteDao.delete(uri) }
    suspend fun isFavorite(uri: String): Boolean = withContext(Dispatchers.IO) { favoriteDao.getByUri(uri) != null }
    suspend fun getAllFavorites(): List<Favorite> = withContext(Dispatchers.IO) { favoriteDao.getAll() }

    suspend fun getAllTags(): List<Tag> = withContext(Dispatchers.IO) { tagDao.getAll() }
    suspend fun addTag(name: String) = withContext(Dispatchers.IO) { tagDao.insert(Tag(name)) }
    suspend fun deleteTag(name: String) = withContext(Dispatchers.IO) { tagDao.delete(name) }

    suspend fun tagMedia(uri: String, tagName: String) = withContext(Dispatchers.IO) { mediaTagDao.insert(MediaTagCrossRef(uri, tagName)) }
    suspend fun untagMedia(uri: String, tagName: String) = withContext(Dispatchers.IO) { mediaTagDao.delete(uri, tagName) }
    suspend fun getTagsForMedia(uri: String): List<String> = withContext(Dispatchers.IO) { mediaTagDao.getTagsForMedia(uri) }
    suspend fun getMediaForTag(tagName: String): List<String> = withContext(Dispatchers.IO) { mediaTagDao.getMediaForTag(tagName) }
    suspend fun isTaggedBy(uri: String, tagName: String): Boolean = withContext(Dispatchers.IO) { mediaTagDao.isTagged(uri, tagName) }
    suspend fun clearAllTagsForMedia(uri: String) = withContext(Dispatchers.IO) { mediaTagDao.deleteAllForMedia(uri) }
    suspend fun clearAllMediaForTag(tagName: String) = withContext(Dispatchers.IO) { mediaTagDao.deleteAllForTag(tagName) }
    suspend fun getAllAssignedTags(): List<String> = withContext(Dispatchers.IO) { mediaTagDao.getAllTags() }
}
