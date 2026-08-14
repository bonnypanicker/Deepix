package com.devomind.gallerysearch

import android.app.ActivityManager
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

class GalleryRepository(
    private val context: Context,
    @Volatile private var imageEncoder: ImageEncoder? = null,
    @Volatile private var textEncoder: TextEncoder? = null
) {
    private val albumCoverStore = AlbumCoverStore(context)

    /** Images per inference batch: OOM-persisted override wins, else scaled to device capability. */
    private val batchSize: Int = BatchSizing.computeBatchSize(context)

    /** Decode/preprocess workers running concurrently ahead of inference. Conservative on
     *  low-RAM devices; shrinks automatically when an OOM pins [batchSize] down since both derive
     *  from the same override. */
    private val decodeConcurrency: Int = computeDecodeConcurrency()

    private fun computeDecodeConcurrency(): Int {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        if (activityManager.isLowRamDevice) return 1
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return minOf(cores, batchSize, MaxDecodeConcurrency)
    }

    data class SemanticSearchHit(val uri: Uri, val score: Float)

    /** A newly persisted CLIP vector, exposed to the face-candidate queue without re-encoding it. */
    data class IndexedEmbedding(val uri: Uri, val vector: FloatArray)

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
        val sizeBytes: Long = 0L,
        val durationMillis: Long = 0L,
        val path: String = "",
        /** MediaStore rotation degrees (0/90/180/270); indexing-only fast path, see [decodeOrientedBitmapForIndexing]. */
        val orientationDegrees: Int = 0,
        /** File's last-modified time. [dateMillis] is the capture date; these differ after an edit. */
        val dateModifiedMillis: Long = 0L
    ) : Parcelable

    data class Album(
        val id: String,
        val name: String,
        val count: Int,
        val coverUri: Uri?,
        val sizeBytes: Long = 0L,
        val isSmart: Boolean = false
    )

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
            MediaStore.Images.Media.DATE_MODIFIED,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.RELATIVE_PATH,
            MediaStore.Images.Media.DATA,
            MediaStore.Images.Media.ORIENTATION
        )
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val items = ArrayList<MediaItem>()

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATE_MODIFIED)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
            val relativePathColumn = cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
            val dataPathColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA)
            val orientationColumn = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdColumn) ?: continue
                if (albumIds.isNotEmpty() && bucketId !in albumIds) continue

                val dateTaken = cursor.getLong(dateTakenColumn)
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000L
                val dateMillis = sanitizeDate(dateTaken, dateAdded)
                items += MediaItem(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                    bucketId = bucketId,
                    bucketName = cursor.getString(bucketNameColumn)?.takeIf { it.isNotBlank() } ?: "Unnamed album",
                    dateMillis = dateMillis,
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn),
                    mimeType = cursor.getString(mimeColumn),
                    displayName = cursor.getString(nameColumn),
                    mediaType = MediaType.Image,
                    sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                    path = cursor.getString(relativePathColumn).orEmpty().ifBlank {
                        cursor.getString(dataPathColumn).orEmpty()
                    },
                    orientationDegrees = if (orientationColumn >= 0) cursor.getInt(orientationColumn) else 0,
                    dateModifiedMillis = if (dateModifiedColumn >= 0) {
                        cursor.getLong(dateModifiedColumn) * 1000L
                    } else {
                        dateMillis
                    }
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
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DATA
        )
        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"
        val items = ArrayList<MediaItem>()

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_TAKEN)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATE_MODIFIED)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val relativePathColumn = cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH)
            val dataPathColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdColumn) ?: continue
                if (albumIds.isNotEmpty() && bucketId !in albumIds) continue

                val dateTaken = cursor.getLong(dateTakenColumn)
                val dateAdded = cursor.getLong(dateAddedColumn) * 1000L
                val dateMillis = sanitizeDate(dateTaken, dateAdded)
                items += MediaItem(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                    bucketId = bucketId,
                    bucketName = cursor.getString(bucketNameColumn)?.takeIf { it.isNotBlank() } ?: "Unnamed album",
                    dateMillis = dateMillis,
                    width = cursor.getInt(widthColumn),
                    height = cursor.getInt(heightColumn),
                    mimeType = cursor.getString(mimeColumn),
                    displayName = cursor.getString(nameColumn),
                    mediaType = MediaType.Video,
                    sizeBytes = cursor.getLong(sizeColumn).coerceAtLeast(0L),
                    durationMillis = cursor.getLong(durationColumn),
                    path = cursor.getString(relativePathColumn).orEmpty().ifBlank {
                        cursor.getString(dataPathColumn).orEmpty()
                    },
                    dateModifiedMillis = if (dateModifiedColumn >= 0) {
                        cursor.getLong(dateModifiedColumn) * 1000L
                    } else {
                        dateMillis
                    }
                )
            }
        }
        return items
    }

    fun getImageUrisForAlbumIds(albumIds: Set<String>): List<Uri> {
        return getImageItemsForAlbumIds(albumIds).map { it.uri }
    }

    fun getAlbums(): List<Album> {
        return buildAlbumsFrom(getAllMediaItemsForAlbumIds(emptySet()))
    }

    /**
     * Decodes [uri] for indexing/search: two-pass bounds decode so the longest edge lands near
     * [MaxBitmapEdge] in a single decode (no large intermediate bitmap on high-MP photos),
     * with EXIF orientation applied so embeddings match how the photo is actually viewed.
     */
    fun loadBitmap(uri: Uri): Bitmap? = decodeOrientedBitmap(uri, MaxBitmapEdge)

    /**
     * Decodes [uri] for face detection. Faces need far more resolution than CLIP indexing:
     * at the 512px [MaxBitmapEdge] cap a face must span ~40px of a 512px frame (~8% of the
     * photo) to clear YuNet's minimum size, which silently drops distant/group faces. The
     * higher [FaceDetectionMaxEdge] cap keeps small faces above the detector's floor while
     * inSampleSize decoding still avoids full-resolution intermediates.
     */
    fun loadBitmapForFaceDetection(uri: Uri): Bitmap? = decodeOrientedBitmap(uri, FaceDetectionMaxEdge)

    /**
     * Indexing-only fast path for face detection. Uses the MediaStore rotation degrees already
     * read into [MediaItem.orientationDegrees], avoiding a fresh EXIF parse / extra stream open on
     * every photo in the background face worker. Falls back to full EXIF handling if the fast path
     * fails so correctness wins over speed.
     */
    fun loadBitmapForFaceDetectionForIndexing(item: MediaItem): Bitmap? =
        decodeOrientedBitmapForIndexing(item.uri, FaceDetectionMaxEdge, item.orientationDegrees)
            ?: loadBitmapForFaceDetection(item.uri)

    /**
     * Builds the embedding index using a parallel decode/preprocess pool feeding batched inference.
     *
     * Architecture:
     * - Feeder coroutine: walks the unindexed items into a bounded work channel.
     * - Decode pool ([decodeConcurrency] coroutines, Dispatchers.IO): each pulls an item, decodes
     *   it, applies orientation, and preprocesses it into a normalized FloatArray. A bitmap never
     *   leaves its worker — decode, preprocess, and recycle all happen in one try/finally.
     * - Consumer (current coroutine): accumulates prepared FloatArrays into [batchSize]-sized
     *   buffers and runs encodeBatchPrepared() on each — no bitmap or preprocessing work happens
     *   on this thread, so it's free to run inference back-to-back.
     * - Save ticker (Dispatchers.IO): persists the index on a wall-clock interval instead of
     *   inline every N items, so a slow write never blocks the next batch's inference.
     *
     * Decode/preprocess is embarrassingly parallel and never touches the encoder's session lock,
     * so it fully overlaps with inference on multi-core devices. Batch composition doesn't affect
     * correctness (embeddings are stored in a URI-keyed map), so workers don't need to preserve
     * input order.
     */
    suspend fun buildIndex(
        items: List<MediaItem>,
        onProgress: (current: Int, total: Int) -> Unit,
        onEmbeddingsStored: suspend (List<IndexedEmbedding>) -> Unit = {}
    ) {
        val uriSet = items.mapTo(HashSet()) { it.uri.toString() }
        // Reconcile against the requested set: keep in-scope embeddings, drop everything else
        // (e.g. photos from a folder the user unchecked).
        val onDisk = loadIndex()
        val loaded = onDisk.filterKeys { it in uriSet }
        synchronized(indexLock) {
            embeddings = LinkedHashMap(loaded)
        }

        val total = items.size
        onProgress(0, total)

        // Collect items that actually need encoding
        val unindexed = items.filter { !containsEmbedding(it.uri.toString()) }

        if (unindexed.isEmpty()) {
            // Nothing new to encode, but persist any pruning so removed folders don't reappear.
            if (loaded.size != onDisk.size) saveIndex(snapshotIndex())
            Log.d(Tag, "All $total images already indexed (pruned ${onDisk.size - loaded.size})")
            onProgress(total, total)
            return
        }

        Log.d(Tag, "Indexing ${unindexed.size} new images (${loaded.size} already cached)")

        val alreadyDone = total - unindexed.size
        var processedNew = 0
        val dirty = AtomicBoolean(false)

        // Report the already-indexed count immediately
        onProgress(alreadyDone, total)

        coroutineScope {
            val inputChannel = Channel<MediaItem>(capacity = decodeConcurrency * 2)
            val outputChannel = Channel<PreparedItem>(capacity = decodeConcurrency * 2)

            // Persist on a wall-clock interval, off the consumer's critical path, instead of a
            // synchronous full-file rewrite every N items blocking the next batch's inference.
            val saveTicker = launch(Dispatchers.IO) {
                while (isActive) {
                    delay(SaveIntervalMillis)
                    if (dirty.compareAndSet(true, false)) {
                        saveIndex(snapshotIndex())
                    }
                }
            }

            // Feeder: hands work items to the decode pool; suspends only on channel backpressure.
            val feeder = launch(Dispatchers.Default) {
                for (item in unindexed) {
                    ensureActive()
                    inputChannel.send(item)
                }
                inputChannel.close()
            }

            // Decode pool: N workers decode + orient + preprocess concurrently, independent of
            // the inference session lock.
            val decodeWorkers = List(decodeConcurrency) {
                launch(Dispatchers.IO) {
                    for (item in inputChannel) {
                        ensureActive()
                        val prepared = runCatching { loadAndPreprocess(item) }
                            .onFailure { Log.w(Tag, "Decode/preprocess failed for ${item.uri}", it) }
                            .getOrNull()
                        if (prepared != null) outputChannel.send(prepared)
                    }
                }
            }
            // Fan-in closer: closes the output channel once every decode worker is done, without
            // blocking the consumer loop below.
            launch(Dispatchers.Default) {
                decodeWorkers.joinAll()
                outputChannel.close()
            }

            // Consumer: buffers prepared items up to batchSize, then runs inference.
            val batchBuffer = ArrayList<PreparedItem>(batchSize)
            suspend fun flushBatch() {
                if (batchBuffer.isEmpty()) return
                val indexed = encodeAndStore(batchBuffer, dirty)
                if (indexed.isNotEmpty()) onEmbeddingsStored(indexed)
                processedNew += batchBuffer.size
                onProgress(alreadyDone + processedNew, total)
                batchBuffer.clear()
            }
            for (prepared in outputChannel) {
                currentCoroutineContext().ensureActive()
                batchBuffer.add(prepared)
                if (batchBuffer.size >= batchSize) flushBatch()
            }
            flushBatch()

            feeder.join()
            saveTicker.cancel()
        }

        saveIndex(snapshotIndex())
    }

    /** Runs inference on a prepared batch and stores valid embeddings; falls back to per-image
     *  encoding (reusing the already-preprocessed data, no re-decode) if the batch call fails. */
    private fun encodeAndStore(batch: List<PreparedItem>, dirty: AtomicBoolean): List<IndexedEmbedding> {
        val encoder = imageEncoder ?: error("Image encoder not attached yet; indexing must wait for model load")
        val indexed = ArrayList<IndexedEmbedding>(batch.size)
        try {
            val results = encoder.encodeBatchPrepared(batch.map { it.floats })
            batch.zip(results).forEach { (entry, embedding) ->
                storeEmbedding(entry.uri, embedding, dirty)?.let(indexed::add)
            }
        } catch (error: Throwable) {
            Log.w(Tag, "Batch encoding failed, falling back to single-image", error)
            for (entry in batch) {
                try {
                    storeEmbedding(entry.uri, encoder.encodePrepared(entry.floats), dirty)?.let(indexed::add)
                } catch (e: Throwable) {
                    Log.w(Tag, "Failed to encode ${entry.uri}", e)
                }
            }
        }
        return indexed
    }

    private fun storeEmbedding(uri: Uri, embedding: FloatArray, dirty: AtomicBoolean): IndexedEmbedding? {
        if (!isEmbeddingValid(embedding)) {
            Log.w(Tag, "Skipping invalid embedding for $uri")
            return null
        }
        synchronized(indexLock) {
            embeddings[uri.toString()] = embedding
        }
        dirty.set(true)
        return IndexedEmbedding(uri, embedding)
    }

    /** Decodes, orients, and preprocesses one image for indexing; the bitmap never leaves this call. */
    private fun loadAndPreprocess(item: MediaItem): PreparedItem? {
        val encoder = imageEncoder ?: return null
        val bitmap = decodeOrientedBitmapForIndexing(item.uri, MaxBitmapEdge, item.orientationDegrees) ?: return null
        return try {
            PreparedItem(item.uri, encoder.preprocess(bitmap))
        } finally {
            bitmap.recycle()
        }
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

    internal fun searchMetadata(query: String, items: List<MediaItem>): List<MetadataSearch.Hit> {
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

    /** Which of [uris] have no stored embedding yet. Reads the in-memory index only. */
    fun unindexedUris(uris: List<Uri>): List<Uri> {
        val indexed = synchronized(indexLock) { HashSet(embeddings.keys) }
        return uris.filter { it.toString() !in indexed }
    }

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

    /** All indexed image embeddings (uri string -> vector), loading the on-disk index if needed. */
    fun allEmbeddings(): Map<String, FloatArray> {
        var snapshot = snapshotIndex()
        if (snapshot.isEmpty()) {
            synchronized(indexLock) {
                if (embeddings.isEmpty()) embeddings = loadIndex()
                snapshot = LinkedHashMap(embeddings)
            }
        }
        return snapshot
    }

    /** Encode arbitrary text to a normalized CLIP embedding, or null if the text encoder isn't ready. */
    fun encodeText(text: String): FloatArray? = textEncoder?.encode(text)

    /** The stored image embedding for a uri, or encode it on demand if the encoder is available. */
    fun imageEmbedding(uri: Uri): FloatArray? {
        allEmbeddings()[uri.toString()]?.let { return it }
        val encoder = imageEncoder ?: return null
        val bitmap = loadBitmap(uri) ?: return null
        val embedding = runCatching { encoder.encode(bitmap) }.getOrNull() ?: return null
        return if (isEmbeddingValid(embedding)) embedding else null
    }

    /**
     * Encodes a specific region of the image for region-scoped image-to-image search.
     * [region] is normalised (0..1) in the displayed (EXIF-oriented) image space, matching what
     * the viewer's crop overlay produces. Always encodes live (never cached) since crops are
     * one-off queries.
     */
    fun imageEmbeddingForRegion(uri: Uri, region: android.graphics.RectF): FloatArray? {
        val encoder = imageEncoder
        if (encoder == null) {
            Log.w(Tag, "imageEmbeddingForRegion: image encoder not ready")
            return null
        }
        val crop = decodeRegionBitmap(uri, region, RegionDecodeMaxEdge)
        if (crop == null) {
            Log.w(Tag, "imageEmbeddingForRegion: failed to decode crop for $uri region=$region")
            return null
        }
        val embedding = runCatching { encoder.encode(crop) }
            .onFailure { Log.w(Tag, "imageEmbeddingForRegion: encode failed", it) }
            .getOrNull()
        crop.recycle()
        if (embedding == null || !isEmbeddingValid(embedding)) {
            Log.w(Tag, "imageEmbeddingForRegion: invalid/null embedding for $uri")
            return null
        }
        return embedding
    }

    /** A small oriented, cropped preview of [region] for the search bar thumbnail. */
    fun regionThumbnail(uri: Uri, region: android.graphics.RectF): Bitmap? =
        decodeRegionBitmap(uri, region, RegionThumbnailMaxEdge)

    /**
     * Decodes the [region] of [uri] as an upright (EXIF-applied) bitmap. Decodes the whole image at
     * a capped resolution, applies orientation, then crops — so the crop rect maps 1:1 onto the
     * displayed photo and the resulting crop is upright (best input for CLIP).
     */
    private fun decodeRegionBitmap(uri: Uri, region: android.graphics.RectF, maxEdge: Int): Bitmap? {
        val oriented = decodeOrientedBitmap(uri, maxEdge) ?: return null
        val w = oriented.width
        val h = oriented.height
        val left = (region.left * w).roundToInt().coerceIn(0, w - 1)
        val top = (region.top * h).roundToInt().coerceIn(0, h - 1)
        val right = (region.right * w).roundToInt().coerceIn(left + 1, w)
        val bottom = (region.bottom * h).roundToInt().coerceIn(top + 1, h)
        val crop = runCatching {
            Bitmap.createBitmap(oriented, left, top, right - left, bottom - top)
        }.getOrNull()
        if (crop == null || crop !== oriented) oriented.recycle()
        return crop
    }

    /** Decodes [uri] downsampled so the longest edge is ~[maxEdge], with EXIF orientation applied. */
    private fun decodeOrientedBitmap(uri: Uri, maxEdge: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW <= 0 || rawH <= 0) return null

            var sample = 1
            while (maxOf(rawW, rawH) / sample > maxEdge) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            applyExifOrientation(decoded, readExifOrientation(uri))
        } catch (t: Throwable) {
            Log.w(Tag, "decodeOrientedBitmap failed for $uri", t)
            null
        }
    }

    /** Best-effort EXIF orientation; never throws (defaults to normal on any failure). */
    private fun readExifOrientation(uri: Uri): Int = runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            androidx.exifinterface.media.ExifInterface(stream).getAttributeInt(
                androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
            )
        } ?: androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
    }.getOrDefault(androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL)

    /** Returns [bitmap] rotated/flipped to upright per the EXIF [orientation]; recycles the source if replaced. */
    private fun applyExifOrientation(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = android.graphics.Matrix()
        when (orientation) {
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            androidx.exifinterface.media.ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
            else -> return bitmap
        }
        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /**
     * Indexing-only fast path: rotates by the MediaStore ORIENTATION degrees already fetched with
     * the media query, skipping the 3rd stream-open + EXIF byte parse that [decodeOrientedBitmap]
     * needs. Loses flip/mirror handling (rare in real EXIF data, already an accepted trade-off for
     * display purposes elsewhere in the app). [loadBitmap]/[decodeRegionBitmap] are untouched and
     * keep full EXIF correctness for the interactive crop-search path.
     */
    private fun decodeOrientedBitmapForIndexing(uri: Uri, maxEdge: Int, degrees: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            val rawW = bounds.outWidth
            val rawH = bounds.outHeight
            if (rawW <= 0 || rawH <= 0) return null

            var sample = 1
            while (maxOf(rawW, rawH) / sample > maxEdge) sample *= 2

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, opts)
            } ?: return null

            applyRotationDegrees(decoded, degrees)
        } catch (t: Throwable) {
            Log.w(Tag, "decodeOrientedBitmapForIndexing failed for $uri", t)
            null
        }
    }

    /** Returns [bitmap] rotated by [degrees] (a MediaStore rotation value: 0/90/180/270); recycles the source if replaced. */
    private fun applyRotationDegrees(bitmap: Bitmap, degrees: Int): Bitmap {
        val normalized = ((degrees % 360) + 360) % 360
        if (normalized == 0) return bitmap
        val matrix = android.graphics.Matrix().apply { postRotate(normalized.toFloat()) }
        val rotated = runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        }.getOrNull() ?: return bitmap
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** Image-to-image search: cosine of [query] against every indexed embedding, ranked desc. */
    fun searchByEmbedding(
        query: FloatArray,
        excludeUri: String? = null,
        floor: Float = 0.5f,
        limit: Int = 500
    ): List<SemanticSearchHit> {
        val snapshot = allEmbeddings()
        if (snapshot.isEmpty()) return emptyList()
        val scored = ArrayList<SemanticSearchHit>(snapshot.size)
        for ((uri, embedding) in snapshot) {
            if (uri == excludeUri) continue
            val score = EmbeddingUtils.cosineSimilarity(query, embedding)
            if (score >= floor) scored.add(SemanticSearchHit(Uri.parse(uri), score))
        }
        scored.sortByDescending { it.score }
        return if (limit > 0) scored.take(limit) else scored
    }

    private fun setMetadataDocuments(documents: Map<String, MetadataSearch.Document>) {
        metadataDocuments = LinkedHashMap(documents)
        metadataSearchIndex = if (metadataDocuments.isEmpty()) null else MetadataSearch.indexFromDocuments(metadataDocuments.values)
    }

    private fun buildAlbumsFrom(items: List<MediaItem>): List<Album> {
        val buckets = LinkedHashMap<String, Album>()
        items.forEach { item ->
            val existing = buckets[item.bucketId]
            if (existing == null) {
                buckets[item.bucketId] = Album(item.bucketId, item.bucketName, 1, item.uri, item.sizeBytes)
            } else {
                buckets[item.bucketId] = existing.copy(
                    count = existing.count + 1,
                    sizeBytes = existing.sizeBytes + item.sizeBytes
                )
            }
        }
        albumCoverStore.cleanup(buckets.keys)
        val itemByUri = items.associateBy { it.uri.toString() }
        val albumsWithOverrides = buckets.values.map { album ->
            val overrideUri = albumCoverStore.getCoverUri(album.id)
            val validOverride = overrideUri?.takeIf { uri ->
                itemByUri[uri.toString()]?.bucketId == album.id
            }
            if (validOverride != null) {
                album.copy(coverUri = validOverride)
            } else {
                album
            }
        }
        return albumsWithOverrides.sortedByDescending { it.count }
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

    /** A URI paired with its preprocessed (normalized) image data, ready for inference. */
    private data class PreparedItem(val uri: Uri, val floats: FloatArray)

    companion object {
        private const val Tag = "GalleryRepository"
        private const val IndexFileName = "embedding_index.bin"
        private const val MetadataIndexFileName = "metadata_index.bin"
        private const val IndexMagic = 0x47534958
        private const val IndexVersion = 2
        private const val MetadataIndexMagic = 0x474d4458
        private const val MetadataIndexVersion = 1
        private const val MaxBitmapEdge = 512
        // Face detection decodes at a higher cap than CLIP indexing (see
        // loadBitmapForFaceDetection). Because inSampleSize is a power of two, the decoded long
        // edge lands anywhere in (FaceDetectionMaxEdge/2, FaceDetectionMaxEdge]; setting the cap
        // to 2x YuNetDetector.MaxLongEdge (1280) guarantees the decode is never *below* the
        // detector's cap, so every photo with enough native pixels runs detection at the full
        // 1280 internal resolution instead of a sample-size-dependent 768-1280.
        internal const val FaceDetectionMaxEdge = 2560
        // Region crops decode at higher resolution than the 512px index bitmaps so small
        // selections still carry enough detail for the 256px CLIP encoder.
        private const val RegionDecodeMaxEdge = 2048
        private const val RegionThumbnailMaxEdge = 256
        private const val SaveIntervalMillis = 10_000L
        private const val MaxDecodeConcurrency = 4
        private const val MaxUriBytes = 4096
        private const val MaxEmbeddingSize = 4096
        private const val MaxTextBytes = 16_384
        private const val MinValidMillis = 631152000000L
        private const val OneDayMillis = 86_400_000L

    }
}
