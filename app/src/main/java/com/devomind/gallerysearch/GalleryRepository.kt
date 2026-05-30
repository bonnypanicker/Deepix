package com.devomind.gallerysearch

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.CoroutineScope
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
    data class Album(val id: String, val name: String, val count: Int)

    private val indexFile = File(context.filesDir, IndexFileName)
    private val indexLock = Any()
    private var embeddings = LinkedHashMap<String, FloatArray>()
    private val stageTimer = StageTimer()

    val indexedCount: Int
        get() = synchronized(indexLock) { embeddings.size }

    fun getAllImageUris(): List<Uri> {
        return getImageUrisForAlbumIds(emptySet())
    }

    fun getImageUrisForAlbumIds(albumIds: Set<String>): List<Uri> {
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }

        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.BUCKET_ID)
        val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"
        val uris = ArrayList<Uri>()

        context.contentResolver.query(collection, projection, null, null, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdColumn) ?: continue
                if (albumIds.isEmpty() || bucketId in albumIds) {
                    uris += ContentUris.withAppendedId(collection, cursor.getLong(idColumn))
                }
            }
        }
        return uris
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
        val collection = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(MediaStore.Images.Media.BUCKET_ID, MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val buckets = LinkedHashMap<String, Album>()

        context.contentResolver.query(collection, projection, null, null, null)?.use { cursor ->
            val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_ID)
            val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val bucketId = cursor.getString(bucketIdColumn) ?: continue
                val bucketName = cursor.getString(bucketNameColumn)?.takeIf { it.isNotBlank() } ?: "Unnamed album"
                val existing = buckets[bucketId]
                if (existing == null) {
                    buckets[bucketId] = Album(bucketId, bucketName, 1)
                } else {
                    buckets[bucketId] = existing.copy(count = existing.count + 1)
                }
            }
        }
        return buckets.values.sortedByDescending { it.count }
    }

    fun loadBitmap(uri: Uri): Bitmap? {
        return runCatching {
            // Pass 1: read dimensions only
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                BitmapFactory.decodeStream(input, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            // Largest power-of-two sample that keeps shortest edge >= target.
            val target = ImageEncoder.ImageSize
            val shortEdge = minOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (shortEdge / (sample * 2) >= target) sample *= 2

            // Pass 2: decode downsampled. Keep ARGB_8888 for safe recall.
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = context.contentResolver.openInputStream(uri).use { input ->
                if (input == null) return null
                BitmapFactory.decodeStream(input, null, opts)
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

            // Fan-out: N decoder coroutines share the batch list via an index channel.
            val batchQueue = Channel<List<Uri>>(capacity = Channel.UNLIMITED).apply {
                batches.forEach { trySend(it) }
                close()
            }

            val decoderCount = Runtime.getRuntime().availableProcessors().coerceIn(2, 6)
            val ioDispatcher = Dispatchers.IO.limitedParallelism(decoderCount)

            val producers = (0 until decoderCount).map {
                launch(ioDispatcher) {
                    for (batch in batchQueue) {
                        currentCoroutineContext().ensureActive()
                        val bitmapEntries = mutableListOf<BitmapEntry>()
                        for (uri in batch) {
                            currentCoroutineContext().ensureActive()
                            val t0 = android.os.SystemClock.elapsedRealtimeNanos()
                            val bitmap = loadBitmap(uri)
                            stageTimer.add(StageTimer.Bucket.DECODE, android.os.SystemClock.elapsedRealtimeNanos() - t0)
                            
                            if (bitmap != null) {
                                bitmapEntries.add(BitmapEntry(uri, bitmap))
                            }
                        }
                        if (bitmapEntries.isNotEmpty()) {
                            channel.send(BatchData(bitmapEntries))
                        }
                    }
                }
            }

            // Close the inference channel only after ALL producers finish.
            launch {
                kotlinx.coroutines.joinAll(*producers.toTypedArray())
                channel.close()
            }

            // Consumer: run batched inference
            for (batchData in channel) {
                currentCoroutineContext().ensureActive()

                val bitmaps = batchData.entries.map { it.bitmap }
                try {
                    val tInferenceStart = android.os.SystemClock.elapsedRealtimeNanos()
                    val embeddings = imageEncoder.encodeBatch(bitmaps)
                    stageTimer.add(StageTimer.Bucket.INFERENCE, android.os.SystemClock.elapsedRealtimeNanos() - tInferenceStart)

                    val newRecordsToPersist = mutableListOf<Pair<String, FloatArray>>()

                    // Store each valid result
                    batchData.entries.zip(embeddings).forEach { (entry, embedding) ->
                        if (isEmbeddingValid(embedding)) {
                            synchronized(indexLock) {
                                this@GalleryRepository.embeddings[entry.uri.toString()] = embedding
                            }
                            newRecordsToPersist.add(entry.uri.toString() to embedding)
                            newSinceLastSave++
                        } else {
                            Log.w(Tag, "Skipping invalid embedding for ${entry.uri}")
                        }
                    }

                    if (newRecordsToPersist.isNotEmpty()) {
                        val tPersistStart = android.os.SystemClock.elapsedRealtimeNanos()
                        appendIndex(newRecordsToPersist)
                        stageTimer.add(StageTimer.Bucket.PERSIST, android.os.SystemClock.elapsedRealtimeNanos() - tPersistStart)
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
                    // Full compact is done at the end, intermediate saves are already appended.
                    newSinceLastSave = 0
                }
                
                stageTimer.onImagesDone(batchData.entries.size)
            }
        }

        // Full rewrite to compact the index at the end of the indexing session
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

    fun getNewImageUris(uris: List<Uri>): List<Uri> {
        val existingUris = synchronized(indexLock) { embeddings.keys }
        return uris.filter { it.toString() !in existingUris }
    }

    fun loadCachedIndexForUris(uris: List<Uri>) {
        val allowed = uris.mapTo(HashSet()) { it.toString() }
        val loaded = loadIndex().filterKeys { it in allowed }
        synchronized(indexLock) {
            embeddings = LinkedHashMap(loaded)
        }
    }

    fun pruneDeletedImages() {
        val snapshot = snapshotIndex()
        if (snapshot.isEmpty()) return
        
        var removed = 0
        val toKeep = LinkedHashMap<String, FloatArray>(snapshot.size)
        
        for ((uriString, embedding) in snapshot) {
            val uri = Uri.parse(uriString)
            // Fast check: can we still open it?
            val exists = runCatching {
                context.contentResolver.openInputStream(uri)?.close()
                true
            }.getOrDefault(false)
            
            if (exists) {
                toKeep[uriString] = embedding
            } else {
                removed++
            }
        }
        
        if (removed > 0) {
            synchronized(indexLock) {
                embeddings = toKeep
            }
            saveIndex(toKeep)
            Log.d(Tag, "Pruned $removed deleted images from index.")
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

    private fun appendIndex(newEntries: List<Pair<String, FloatArray>>) {
        if (!indexFile.exists()) {
            // Write initial header if file doesn't exist
            saveIndex(emptyMap()) 
        }
        
        runCatching {
            DataOutputStream(BufferedOutputStream(java.io.FileOutputStream(indexFile, true))).use { out ->
                for ((uri, emb) in newEntries) {
                    val b = uri.toByteArray(Charsets.UTF_8)
                    out.writeInt(b.size)
                    out.write(b)
                    out.writeInt(emb.size)
                    for (v in emb) out.writeFloat(v)
                }
            }
        }.onFailure { error ->
            Log.w(Tag, "Failed to append to index", error)
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
        private const val MaxBitmapEdge = 320 // was 512
        private const val SaveEvery = 20
        private const val MaxUriBytes = 4096
        private const val MaxEmbeddingSize = 4096

        /** Number of images per inference batch. Start at 4, reduce to 2 if OOM occurs. */
        @Volatile var BatchSize = 4

        /** Pipeline channel buffer — producer can be this many batches ahead of consumer. */
        private const val PipelineBuffer = 4
    }
}
