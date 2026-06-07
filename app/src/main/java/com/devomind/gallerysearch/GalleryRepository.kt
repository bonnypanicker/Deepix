package com.devomind.gallerysearch

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Parcelable
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
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
    @Volatile private var imageEncoder: ImageEncoder? = null,
    @Volatile private var textEncoder: TextEncoder? = null
) {
    data class SemanticSearchHit(val uri: Uri, val score: Float)

    /** Attaches the MobileCLIP encoders once they finish loading on a background thread. */
    fun attachEncoders(image: ImageEncoder, text: TextEncoder?) {
        this.imageEncoder = image
        this.textEncoder = text
    }

    @Parcelize
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
    ) : Parcelable

    data class Album(val id: String, val name: String, val count: Int, val coverUri: Uri?)

    data class Snapshot(
        val albums: List<Album>,
        val imageItems: List<MediaItem>,
        val collectionItems: List<MediaItem>,
        val videoItems: List<MediaItem>
    )

    @Parcelize
    enum class MediaType : Parcelable {
        Image,
        Video
    }

    private val indexFile = File(context.filesDir, IndexFileName)
    private val metadataIndexFile = File(context.filesDir, MetadataIndexFileName)
    private val indexLock = Any()
    private val metadataLock = Any()
    private var embeddings = LinkedHashMap<String, FloatArray>()
    private var metadataDocuments = LinkedHashMap<String, MetadataSearch.Document>()
    @Volatile private var metadataSearchIndex: MetadataSearch.Index? = null

    val indexedCount: Int
        get() = synchronized(indexLock) { embeddings.size }

    val metadataIndexedCount: Int
        get() = synchronized(metadataLock) { metadataDocuments.size }

    fun getAllImageUris(): List<Uri> {
        return getImageUrisForAlbumIds(emptySet())
    }

    fun getAllMediaItemsForAlbumIds(albumIds: Set<String>): List<MediaItem> {
        return (queryImageItems(albumIds) + queryVideoItems(albumIds))
            .sortedByDescending { it.dateMillis }
    }

    fun loadSnapshot(albumIds: Set<String>): Snapshot {
        val images = queryImageItems(albumIds)
        val videos = queryVideoItems(albumIds)
        val allItems = (images + videos).sortedByDescending { it.dateMillis }
        return Snapshot(
            albums = buildAlbumsFrom(allItems),
            imageItems = images,
            collectionItems = allItems,
            videoItems = videos
        )
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
                    dateMillis = sanitizeDate(dateTaken, dateAdded),
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
                    dateMillis = sanitizeDate(dateTaken, dateAdded),
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
        return buildAlbumsFrom(getAllMediaItemsForAlbumIds(emptySet()))
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
                    val encoder = imageEncoder ?: error("Image encoder not attached yet; indexing must wait for model load")
                    val embeddings = encoder.encodeBatch(bitmaps)

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
                            val encoder = imageEncoder ?: continue
                            val embedding = encoder.encode(entry.bitmap)
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

    fun search(query: String): List<SemanticSearchHit> {
        val textEncoder = textEncoder ?: return emptyList()
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
            .map { SemanticSearchHit(uri = Uri.parse(it.first), score = it.second) }

        return results
    }

    fun searchMetadata(query: String, items: List<MediaItem>): List<MetadataSearch.Hit> {
        if (items.isEmpty()) return emptyList()

        var index = metadataSearchIndex
        if (index == null) {
            synchronized(metadataLock) {
                if (metadataDocuments.isEmpty()) {
                    setMetadataDocuments(loadMetadataIndex())
                }
                index = metadataSearchIndex
            }
        }

        val allowedUris = items.mapTo(HashSet(items.size)) { it.uri.toString() }
        val loadedIndex = index
        if (loadedIndex != null) {
            return loadedIndex.search(query, allowedUris)
        }

        // First run fallback before the persistent metadata index is ready.
        return MetadataSearch.search(query, items)
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

    fun loadCachedMetadataIndexForUris(uris: List<Uri>) {
        val allowed = uris.mapTo(HashSet()) { it.toString() }
        val loaded = loadMetadataIndex().filterKeys { it in allowed }
        synchronized(metadataLock) {
            setMetadataDocuments(loaded)
        }
    }

    fun rebuildMetadataIndex(items: List<MediaItem>) {
        val documents = MetadataSearch.buildDocuments(items).associateByTo(LinkedHashMap(items.size)) { it.uri }
        synchronized(metadataLock) {
            setMetadataDocuments(documents)
        }
        saveMetadataIndex(documents)
    }

    private fun snapshotIndex(): LinkedHashMap<String, FloatArray> =
        synchronized(indexLock) { LinkedHashMap(embeddings) }

    private fun setMetadataDocuments(documents: Map<String, MetadataSearch.Document>) {
        metadataDocuments = LinkedHashMap(documents)
        metadataSearchIndex = if (metadataDocuments.isEmpty()) null else MetadataSearch.indexFromDocuments(metadataDocuments.values)
    }

    private fun buildAlbumsFrom(items: List<MediaItem>): List<Album> {
        val buckets = LinkedHashMap<String, Album>()
        items.forEach { item ->
            val existing = buckets[item.bucketId]
            if (existing == null) {
                buckets[item.bucketId] = Album(item.bucketId, item.bucketName, 1, item.uri)
            } else {
                buckets[item.bucketId] = existing.copy(count = existing.count + 1)
            }
        }
        return buckets.values.sortedByDescending { it.count }
    }

    private fun sanitizeDate(dateTakenMs: Long, dateAddedMs: Long): Long {
        val nowPlusDay = System.currentTimeMillis() + OneDayMillis
        return when {
            dateTakenMs in MinValidMillis..nowPlusDay -> dateTakenMs
            dateAddedMs in MinValidMillis..nowPlusDay -> dateAddedMs
            dateAddedMs > 0L -> dateAddedMs.coerceIn(MinValidMillis, nowPlusDay)
            else -> System.currentTimeMillis()
        }
    }

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

    private fun loadMetadataIndex(): LinkedHashMap<String, MetadataSearch.Document> {
        if (!metadataIndexFile.exists()) return LinkedHashMap()

        return runCatching {
            DataInputStream(BufferedInputStream(metadataIndexFile.inputStream())).use { input ->
                val magic = input.readInt()
                val version = input.readInt()
                if (magic != MetadataIndexMagic || version != MetadataIndexVersion) {
                    throw IllegalStateException("Unsupported metadata index file version.")
                }

                val count = input.readInt().coerceAtLeast(0)
                val loaded = LinkedHashMap<String, MetadataSearch.Document>(count)
                repeat(count) {
                    val document = MetadataSearch.Document(
                        uri = readIndexString(input),
                        dateMillis = input.readLong(),
                        width = input.readInt(),
                        height = input.readInt(),
                        displayName = readIndexString(input),
                        displayNameWithoutExt = readIndexString(input),
                        bucketName = readIndexString(input),
                        mimeType = readIndexString(input),
                        mimeSubtype = readIndexString(input),
                        extension = readIndexString(input),
                        orientation = readIndexString(input),
                        year = input.readInt(),
                        month = input.readInt(),
                        day = input.readInt(),
                        monthName = readIndexString(input),
                        dayName = readIndexString(input),
                        id = readIndexString(input),
                        searchableText = readIndexString(input)
                    )
                    loaded[document.uri] = document
                }
                loaded
            }
        }.onFailure { error ->
            Log.w(Tag, "Ignoring corrupt metadata index.", error)
            metadataIndexFile.delete()
        }.getOrDefault(LinkedHashMap())
    }

    private fun saveMetadataIndex(index: Map<String, MetadataSearch.Document>) {
        val tmpFile = File(metadataIndexFile.parentFile, "$MetadataIndexFileName.tmp")
        runCatching {
            DataOutputStream(BufferedOutputStream(tmpFile.outputStream())).use { output ->
                output.writeInt(MetadataIndexMagic)
                output.writeInt(MetadataIndexVersion)
                output.writeInt(index.size)
                for (document in index.values) {
                    writeIndexString(output, document.uri)
                    output.writeLong(document.dateMillis)
                    output.writeInt(document.width)
                    output.writeInt(document.height)
                    writeIndexString(output, document.displayName)
                    writeIndexString(output, document.displayNameWithoutExt)
                    writeIndexString(output, document.bucketName)
                    writeIndexString(output, document.mimeType)
                    writeIndexString(output, document.mimeSubtype)
                    writeIndexString(output, document.extension)
                    writeIndexString(output, document.orientation)
                    output.writeInt(document.year)
                    output.writeInt(document.month)
                    output.writeInt(document.day)
                    writeIndexString(output, document.monthName)
                    writeIndexString(output, document.dayName)
                    writeIndexString(output, document.id)
                    writeIndexString(output, document.searchableText)
                }
            }
            if (metadataIndexFile.exists()) {
                metadataIndexFile.delete()
            }
            tmpFile.renameTo(metadataIndexFile)
        }.onFailure { error ->
            Log.w(Tag, "Failed to save metadata index.", error)
            tmpFile.delete()
        }
    }

    private fun readIndexString(input: DataInputStream): String {
        val byteCount = input.readInt()
        if (byteCount < 0 || byteCount > MaxTextBytes) {
            throw EOFException("Invalid text length.")
        }
        if (byteCount == 0) return ""
        val bytes = ByteArray(byteCount)
        input.readFully(bytes)
        return bytes.toString(Charsets.UTF_8)
    }

    private fun writeIndexString(output: DataOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    /** Holds a URI + its loaded bitmap for batch processing. */
    private data class BitmapEntry(val uri: Uri, val bitmap: Bitmap)

    /** A prepared batch ready for inference. */
    private data class BatchData(val entries: List<BitmapEntry>)

    companion object {
        private const val Tag = "GalleryRepository"
        private const val IndexFileName = "embedding_index.bin"
        private const val MetadataIndexFileName = "metadata_index.bin"
        private const val IndexMagic = 0x47534958
        private const val IndexVersion = 2
        private const val MetadataIndexMagic = 0x474d4458
        private const val MetadataIndexVersion = 1
        private const val MaxBitmapEdge = 512
        private const val SaveEvery = 20
        private const val MaxUriBytes = 4096
        private const val MaxEmbeddingSize = 4096
        private const val MaxTextBytes = 16_384
        private const val MinValidMillis = 631152000000L
        private const val OneDayMillis = 86_400_000L

        /** Number of images per inference batch. Start at 4, reduce to 2 if OOM occurs. */
        const val BatchSize = 4

        /** Pipeline channel buffer — producer can be this many batches ahead of consumer. */
        private const val PipelineBuffer = 2
    }
}
