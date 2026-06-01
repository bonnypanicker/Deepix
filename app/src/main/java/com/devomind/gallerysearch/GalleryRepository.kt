package com.devomind.gallerysearch

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

class GalleryRepository(
    private val context: Context,
    private val imageEncoder: ImageEncoder,
    private val textEncoder: TextEncoder
) {
    data class MediaItem(
        val uri: Uri,
        val bucketId: String,
        val bucketName: String,
        val dateMillis: Long,
        val width: Int,
        val height: Int,
        val mimeType: String?,
        val displayName: String?,
        val mediaType: MediaType,
        val durationMillis: Long = 0L
    )

    data class Album(val id: String, val name: String, val count: Int, val coverUri: Uri?)

    enum class MediaType {
        Image,
        Video
    }

    private val indexFile = File(context.filesDir, IndexFileName)
    private val indexLock = Any()
    private var embeddings = LinkedHashMap<String, FloatArray>()

    val indexedCount: Int
        get() = synchronized(indexLock) { embeddings.size }

    fun getAllImageUris(): List<Uri> {
        return getImageUrisForAlbumIds(emptySet())
    }

    fun getAllMediaItemsForAlbumIds(albumIds: Set<String>): List<MediaItem> {
        return (queryImageItems(albumIds) + queryVideoItems(albumIds))
            .sortedByDescending { it.dateMillis }
    }

    fun getImageItemsForAlbumIds(albumIds: Set<String>): List<MediaItem> {
        return queryImageItems(albumIds)
    }

    fun getVideoItemsForAlbumIds(albumIds: Set<String>): List<MediaItem> {
        return queryVideoItems(albumIds)
    }

    private fun queryImageItems(albumIds: Set<String>): List<MediaItem> {
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DISPLAY_NAME
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val items = ArrayList<MediaItem>()

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdColumn) ?: continue
                if (albumIds.isNotEmpty() && bucketId !in albumIds) continue

                val dateTaken = cursor.getLong(dateTakenColumn)
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000L
                items += MediaItem(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                    bucketId = bucketId,
                    bucketName = cursor.getString(bucketNameColumn)?.takeIf { it.isNotBlank() } ?: "Unnamed album",
                    dateMillis = if (dateTaken > 0L) dateTaken else dateAdded,
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn),
                    mimeType = cursor.getString(mimeColumn),
                    displayName = cursor.getString(nameColumn),
                    mediaType = MediaType.Image
                )
            }
        }
        return items
    }

    private fun queryVideoItems(albumIds: Set<String>): List<MediaItem> {
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val items = ArrayList<MediaItem>()

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdColumn) ?: continue
                if (albumIds.isNotEmpty() && bucketId !in albumIds) continue

                val dateTaken = cursor.getLong(dateTakenColumn)
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000L
                items += MediaItem(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                    bucketId = bucketId,
                    bucketName = cursor.getString(bucketNameColumn)?.takeIf { it.isNotBlank() } ?: "Unnamed album",
                    dateMillis = if (dateTaken > 0L) dateTaken else dateAdded,
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn),
                    mimeType = cursor.getString(mimeColumn),
                    displayName = cursor.getString(nameColumn),
                    mediaType = MediaType.Video,
                    durationMillis = cursor.getLong(durationColumn)
                )
            }
        }
        return items
    }

    fun getImageUrisForAlbumIds(albumIds: Set<String>): List<Uri> {
        return getImageItemsForAlbumIds(albumIds).map { it.uri }
    }

    /**
     * Returns only image URIs added to MediaStore after the given timestamp.
     * Used for incremental indexing — skip photos that were already indexed.
     */
    fun getNewImageUris(albumIds: Set<String>, sinceTimestamp: Long): List<Uri> {
        if (sinceTimestamp <= 0L) return getImageUrisForAlbumIds(albumIds)

        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.DATE_ADDED
        )
        // DATE_ADDED is stored as seconds since epoch in MediaStore
        val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
        val selectionArgs = arrayOf((sinceTimestamp / 1000).toString())
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} ASC"

        val uris = ArrayList<Uri>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdColumn) ?: continue
                if (albumIds.isEmpty() || bucketId in albumIds) {
                    uris += ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                }
            }
        }
        Log.d(Tag, "Incremental query: ${uris.size} new images since ${sinceTimestamp}")
        return uris
    }

    fun getAlbums(): List<Album> {
        val buckets = LinkedHashMap<String, Album>()
        getAllMediaItemsForAlbumIds(emptySet()).forEach { item ->
            val existing = buckets[item.bucketId]
            if (existing == null) {
                buckets[item.bucketId] = Album(item.bucketId, item.bucketName, 1, item.uri)
            } else {
                buckets[item.bucketId] = existing.copy(count = existing.count + 1)
            }
        }
        return buckets.values.sortedByDescending { it.count }
    }

    fun loadBitmap(uri: Uri): Bitmap? {
        return runCatching {
            val options = BitmapFactory.Options().apply {
                inSampleSize = 4
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                BitmapFactory.decodeStream(input, null, options)
            } ?: return null

            scaleToMaxEdge(decoded, MaxBitmapEdge)
        }.onFailure { error ->
            Log.w(Tag, "Failed to decode $uri", error)
        }.getOrNull()
    }

    /**
     * Builds the embedding index using batched inference and pipelined preprocessing.
     *
     * Architecture:
     * - Producer coroutine (Dispatchers.IO): loads bitmaps from disk in batches
     * - Consumer (current coroutine): runs encodeBatch() on each prepared batch
     * - Channel with capacity=2 lets the producer stay 1-2 batches ahead
     *
     * This overlaps IO (bitmap loading) with compute (inference), and
     * batching reduces per-image ONNX framework overhead.
     */
    suspend fun buildIndex(uris: List<Uri>, onProgress: (current: Int, total: Int) -> Unit) {
        val uriKeys = uris.map { it.toString() }
        val uriSet = uriKeys.toSet()
        val loaded = loadIndex().filterKeys { it in uriSet }
        synchronized(indexLock) {
            embeddings = LinkedHashMap(loaded)
        }

        val total = uris.size
        onProgress(0, total)

        // Collect URIs that actually need encoding
        val unindexed = uris.filter { !containsEmbedding(it.toString()) }

        if (unindexed.isEmpty()) {
            Log.d(Tag, "All $total images already indexed — nothing to do")
            onProgress(total, total)
            return
        }

        Log.d(Tag, "Indexing ${unindexed.size} new images (${loaded.size} already cached)")

        val alreadyDone = total - unindexed.size
        var processedNew = 0
        var newSinceLastSave = 0

        // Report the already-indexed count immediately
        onProgress(alreadyDone, total)

        val batches = unindexed.chunked(BatchSize)

        // Pipeline: producer loads bitmaps, consumer runs inference
        coroutineScope {
            val channel = Channel<BatchData>(capacity = PipelineBuffer)

            // Producer: load and preprocess bitmaps on IO threads
            val producer = launch(Dispatchers.IO) {
                for (batch in batches) {
                    currentCoroutineContext().ensureActive()
                    val bitmapEntries = mutableListOf<BitmapEntry>()
                    for (uri in batch) {
                        currentCoroutineContext().ensureActive()
                        val bitmap = loadBitmap(uri)
                        if (bitmap != null) {
                            bitmapEntries.add(BitmapEntry(uri, bitmap))
                        }
                    }
                    if (bitmapEntries.isNotEmpty()) {
                        channel.send(BatchData(bitmapEntries))
                    }
                }
                channel.close()
            }

            // Consumer: run batched inference
            for (batchData in channel) {
                currentCoroutineContext().ensureActive()

                val bitmaps = batchData.entries.map { it.bitmap }
                try {
                    val embeddings = imageEncoder.encodeBatch(bitmaps)

                    // Store each valid result
                    batchData.entries.zip(embeddings).forEach { (entry, embedding) ->
                        if (isEmbeddingValid(embedding)) {
                            synchronized(indexLock) {
                                this@GalleryRepository.embeddings[entry.uri.toString()] = embedding
                            }
                            newSinceLastSave++
                        } else {
                            Log.w(Tag, "Skipping invalid embedding for ${entry.uri}")
                        }
                    }
                } catch (error: Throwable) {
                    Log.w(Tag, "Batch encoding failed, falling back to single-image", error)
                    // Fallback: encode one at a time
                    for (entry in batchData.entries) {
                        try {
                            val embedding = imageEncoder.encode(entry.bitmap)
                            if (isEmbeddingValid(embedding)) {
                                synchronized(indexLock) {
                                    this@GalleryRepository.embeddings[entry.uri.toString()] = embedding
                                }
                                newSinceLastSave++
                            }
                        } catch (e: Throwable) {
                            Log.w(Tag, "Failed to encode ${entry.uri}", e)
                        }
                    }
                } finally {
                    // Recycle all bitmaps
                    bitmaps.forEach { it.recycle() }
                }

                processedNew += batchData.entries.size
                onProgress(alreadyDone + processedNew, total)

                if (newSinceLastSave >= SaveEvery) {
                    saveIndex(snapshotIndex())
                    newSinceLastSave = 0
                }
            }

            producer.join()
        }

        saveIndex(snapshotIndex())
    }

    fun search(query: String): List<Uri> {
        var snapshot = snapshotIndex()
        if (snapshot.isEmpty()) {
            synchronized(indexLock) {
                if (embeddings.isEmpty()) embeddings = loadIndex()
                snapshot = LinkedHashMap(embeddings)
            }
        }
        if (snapshot.isEmpty()) return emptyList()

        val variants = buildQueryVariants(query)
        val bestScores = HashMap<String, Float>(snapshot.size)
        for (variant in variants) {
            val queryEmbedding = textEncoder.encode(variant)
            for ((uri, embedding) in snapshot) {
                val score = EmbeddingUtils.cosineSimilarity(queryEmbedding, embedding)
                val current = bestScores[uri]
                if (current == null || score > current) {
                    bestScores[uri] = score
                }
            }
        }

        val ranked = bestScores.entries.asSequence()
            .map { it.key to it.value }
            .sortedByDescending { it.second }
            .toList()

        val bestScore = ranked.firstOrNull()?.second ?: return emptyList()
        val relativeCutoff = bestScore * SearchTuning.MaxScoreDropRatio

        val results = ranked
            .filter { it.second >= relativeCutoff }
            .filter { it.second >= SearchTuning.ScoreThreshold }
            .map { Uri.parse(it.first) }

        return results
    }

    private fun containsEmbedding(uri: String): Boolean =
        synchronized(indexLock) { embeddings.containsKey(uri) }

    fun loadCachedIndexForUris(uris: List<Uri>) {
        val allowed = uris.mapTo(HashSet()) { it.toString() }
        val loaded = loadIndex().filterKeys { it in allowed }
        synchronized(indexLock) {
            embeddings = LinkedHashMap(loaded)
        }
    }

    private fun snapshotIndex(): LinkedHashMap<String, FloatArray> =
        synchronized(indexLock) { LinkedHashMap(embeddings) }

    private fun buildQueryVariants(query: String): List<String> {
        val cleaned = query.trim().replace(Regex("""\s+"""), " ")
        if (cleaned.isBlank()) return listOf(query)
        return listOf(
            cleaned,
            "a photo of $cleaned",
            "a picture of $cleaned",
            "$cleaned photo"
        ).distinct()
    }

    private fun isEmbeddingValid(embedding: FloatArray): Boolean {
        if (embedding.isEmpty()) return false
        if (embedding.any { it.isNaN() || it.isInfinite() }) return false
        if (embedding.all { abs(it) < 1e-8f }) return false
        return true
    }

    private fun scaleToMaxEdge(bitmap: Bitmap, maxEdge: Int): Bitmap {
        val currentMaxEdge = maxOf(bitmap.width, bitmap.height)
        if (currentMaxEdge <= maxEdge) return bitmap

        val scale = maxEdge.toFloat() / currentMaxEdge.toFloat()
        val width = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val height = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        if (scaled !== bitmap) bitmap.recycle()
        return scaled
    }

    private fun loadIndex(): LinkedHashMap<String, FloatArray> {
        if (!indexFile.exists()) return LinkedHashMap()

        return runCatching {
            DataInputStream(BufferedInputStream(indexFile.inputStream())).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                if (magic != IndexMagic || version != IndexVersion) {
                    throw IllegalStateException("Unsupported index file version.")
                }

                val count = input.readInt().coerceAtLeast(0)
                val loaded = LinkedHashMap<String, FloatArray>(count)
                repeat(count) {
                    val uriLength = input.readInt()
                    if (uriLength <= 0 || uriLength > MaxUriBytes) throw EOFException("Invalid URI length.")
                    val uriBytes = ByteArray(uriLength)
                    input.readFully(uriBytes)
                    val uri = uriBytes.toString(Charsets.UTF_8)

                    val embeddingSize = input.readInt()
                    if (embeddingSize <= 0 || embeddingSize > MaxEmbeddingSize) {
                        throw EOFException("Invalid embedding size.")
                    }
                    val embedding = FloatArray(embeddingSize) { input.readFloat() }
                    loaded[uri] = embedding
                }
                loaded
            }
        }.onFailure { error ->
            Log.w(Tag, "Ignoring corrupt embedding index.", error)
            indexFile.delete()
        }.getOrDefault(LinkedHashMap())
    }

    private fun saveIndex(index: Map<String, FloatArray>) {
        val tmpFile = File(indexFile.parentFile, "$IndexFileName.tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(tmpFile.outputStream())).use { output ->
                output.writeInt(IndexMagic)
                output.writeInt(IndexVersion)
                output.writeInt(index.size)
                for ((uri, embedding) in index) {
                    val uriBytes = uri.toByteArray(Charsets.UTF_8)
                    output.writeInt(uriBytes.size)
                    output.write(uriBytes)
                    output.writeInt(embedding.size)
                    for (value in embedding) {
                        output.writeFloat(value)
                    }
                }
            }
            if (indexFile.exists()) {
                indexFile.delete()
            }
            tmpFile.renameTo(indexFile)
        }.onFailure { error ->
            Log.w(Tag, "Failed to save embedding index.", error)
            tmpFile.delete()
        }
    }

    /** Holds a URI + its loaded bitmap for batch processing. */
    private data class BitmapEntry(val uri: Uri, val bitmap: Bitmap)

    /** A prepared batch ready for inference. */
    private data class BatchData(val entries: List<BitmapEntry>)

    companion object {
        private const val Tag = "GalleryRepository"
        private const val IndexFileName = "embedding_index.bin"
        private const val IndexMagic = 0x47534958
        private const val IndexVersion = 2
        private const val MaxBitmapEdge = 512
        private const val SaveEvery = 20
        private const val MaxUriBytes = 4096
        private const val MaxEmbeddingSize = 4096

        /** Number of images per inference batch. Start at 4, reduce to 2 if OOM occurs. */
        const val BatchSize = 4

        /** Pipeline channel buffer — producer can be this many batches ahead of consumer. */
        private const val PipelineBuffer = 2
    }
}
