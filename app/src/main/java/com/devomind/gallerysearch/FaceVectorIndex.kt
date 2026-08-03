package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.CRC32

/**
 * Phase 2 deliverable #11 — flat cosine-similarity vector index for face embeddings.
 *
 * Backed by a READ_ONLY memory-mapped file (`filesDir/face_vector_index.bin`) so bulk reads
 * (centroid computation, exemplar-vote, startup consistency check) avoid both heap allocation of
 * a full copy and the per-face JSON parse the Room `embeddingJson` column would otherwise cost.
 *
 * Durability model: the mmap is READ_ONLY; writes (put/remove) stage into in-memory pending maps
 * and are persisted by [flush], which rewrites the whole file atomically (`.tmp` → rename) and
 * re-maps. This is file-level copy-on-write: a crash never leaves a partially-written file — at
 * worst it leaves the pre-flush version, and the Phase 2 startup consistency check
 * ([FaceIndexConsistency]) backfills any vectors lost since the last flush from the durable
 * Room `embeddingJson` (which is kept as the source of truth through Phase 2).
 *
 * Integrity: a CRC32 over the entry region is stored in the header; [verifyChecksum] validates it
 * on load. On mismatch the index is treated as empty (corruption recovery is Phase 3's job — here
 * we just refuse to serve stale data and let the consistency check rebuild from Room).
 *
 * Keyed by `faceId: Long` (the Room `faces` PK). 512-dim L2-normalized MobileFaceNet embeddings.
 */
class FaceVectorIndex(private val context: Context) : AutoCloseable {

    private val file = File(context.filesDir, FileName)
    private val lock = ReentrantLock()

    @Volatile private var mapped: MappedByteBuffer? = null
    /** faceId → entry index (0-based) into the mmap entry region. Guarded by [lock]. */
    private val idToIndex = HashMap<Long, Int>()
    @Volatile private var persistedCount: Int = 0
    @Volatile private var mappedDim: Int = EmbeddingDim

    private val pendingPuts = HashMap<Long, FloatArray>()
    private val pendingRemoves = HashSet<Long>()

    init { reload() }

    /** Number of vectors currently available (persisted + staged, minus pending removes). */
    fun size(): Int = lock.withLock {
        persistedCount + pendingPuts.size - pendingRemoves.count { it !in pendingPuts }
    }

    /** True when [faceId] has a vector (persisted or staged). */
    fun contains(faceId: Long): Boolean = lock.withLock {
        faceId in pendingPuts || (faceId in idToIndex && faceId !in pendingRemoves)
    }

    /**
     * Read the embedding for [faceId], or null if absent. Returns a fresh FloatArray copy each
     * call (callers may hold it for the duration of a match; it is not cached to avoid aliasing).
     */
    fun get(faceId: Long): FloatArray? = lock.withLock {
        pendingPuts[faceId]?.copyOf()?.let { return it }
        if (faceId in pendingRemoves) return null
        val index = idToIndex[faceId] ?: return null
        val buf = mapped ?: return null
        val dim = mappedDim
        val offset = HeaderSize + index * entrySize(dim)
        buf.position(offset + FaceIdBytes)
        val out = FloatArray(dim)
        buf.asFloatBuffer().get(out)
        return out
    }

    /** Stage a vector for [faceId]; persisted on the next [flush]. Overwrites any prior entry. */
    fun put(faceId: Long, embedding: FloatArray) {
        require(embedding.size == EmbeddingDim) { "Expected $EmbeddingDim-dim embedding, got ${embedding.size}" }
        lock.withLock {
            pendingPuts[faceId] = embedding
            pendingRemoves.remove(faceId)
        }
    }

    /** Stage removal of [faceId]; persisted on the next [flush]. */
    fun remove(faceId: Long) = lock.withLock {
        pendingPuts.remove(faceId)
        pendingRemoves.add(faceId)
    }

    /**
     * Iterate over all currently-available vectors. Each entry's FloatArray is a fresh copy.
     * Used by the consistency check and (optionally) full re-cluster passes.
     */
    fun allEntries(): Sequence<Pair<Long, FloatArray>> = sequence {
        val snapshot: List<Pair<Long, FloatArray>> = lock.withLock {
            val out = ArrayList<Pair<Long, FloatArray>>(size())
            for ((id, emb) in pendingPuts) out.add(id to emb.copyOf())
            val buf = mapped
            if (buf != null) {
                val dim = mappedDim
                for ((id, index) in idToIndex) {
                    if (id in pendingRemoves || id in pendingPuts) continue
                    val offset = HeaderSize + index * entrySize(dim)
                    buf.position(offset + FaceIdBytes)
                    val emb = FloatArray(dim)
                    buf.asFloatBuffer().get(emb)
                    out.add(id to emb)
                }
            }
            out
        }
        yieldAll(snapshot)
    }

    /** Validate the on-disk CRC32. False when the file is missing, truncated, or corrupted. */
    fun verifyChecksum(): Boolean = lock.withLock {
        val buf = mapped ?: return false
        buf.position(0)
        buf.order(ByteOrder.LITTLE_ENDIAN)
        val magic = buf.int
        if (magic != Magic) return false
        buf.int // version
        val count = buf.int
        val dim = buf.int
        val storedChecksum = buf.int
        if (count < 0 || dim <= 0 || dim > MaxDim) return false
        val regionBytes = count.toLong() * entrySize(dim)
        if (regionBytes + HeaderSize > buf.limit().toLong()) return false
        val computed = crc32(buf, HeaderSize, regionBytes.toInt())
        computed == storedChecksum
    }

    /** Persist all staged puts/removes atomically and re-map. No-op when nothing is staged. */
    fun flush() {
        lock.withLock {
            if (pendingPuts.isEmpty() && pendingRemoves.isEmpty()) return
            val dim = EmbeddingDim
            // Merge: existing persisted entries (minus removes) + pending puts.
            val merged = LinkedHashMap<Long, FloatArray>(persistedCount + pendingPuts.size)
            val buf = mapped
            if (buf != null) {
                for ((id, index) in idToIndex) {
                    if (id in pendingRemoves || id in pendingPuts) continue
                    val offset = HeaderSize + index * entrySize(dim)
                    buf.position(offset + FaceIdBytes)
                    val emb = FloatArray(dim)
                    buf.asFloatBuffer().get(emb)
                    merged[id] = emb
                }
            }
            merged.putAll(pendingPuts)

            writeAtomically(merged, dim)

            pendingPuts.clear()
            pendingRemoves.clear()
            reloadLocked()
        }
    }

    /** Drop the in-memory map and reload from disk. Mainly for tests / repair flows. */
    fun reload() = lock.withLock { reloadLocked() }

    private fun reloadLocked() {
        mapped?.let { unmap(it) }
        mapped = null
        idToIndex.clear()
        persistedCount = 0
        if (!file.exists()) return
        runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val channel = raf.channel
                val length = channel.size()
                if (length < HeaderSize) return@use
                val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, length)
                buf.order(ByteOrder.LITTLE_ENDIAN)
                val magic = buf.int
                if (magic != Magic) {
                    Log.w(Tag, "Bad magic 0x${magic.toString(16)}; treating index as empty.")
                    return@use
                }
                val version = buf.int
                val count = buf.int
                val dim = buf.int
                val checksum = buf.int
                if (version != Version) {
                    Log.w(Tag, "Unsupported version $version; treating index as empty.")
                    return@use
                }
                if (count < 0 || dim != EmbeddingDim || count.toLong() * entrySize(dim) + HeaderSize > length) {
                    Log.w(Tag, "Header inconsistent (count=$count, dim=$dim, len=$length); ignoring.")
                    return@use
                }
                val expected = crc32(buf, HeaderSize, count * entrySize(dim))
                if (expected != checksum) {
                    Log.w(Tag, "Checksum mismatch (stored=$checksum, computed=$expected); ignoring index.")
                    return@use
                }
                mappedDim = dim
                persistedCount = count
                for (i in 0 until count) {
                    val offset = HeaderSize + i * entrySize(dim)
                    val id = buf.getLong(offset)
                    idToIndex[id] = i
                }
                mapped = buf
            }
        }.onFailure { Log.w(Tag, "Failed to mmap face vector index.", it) }
    }

    private fun writeAtomically(entries: Map<Long, FloatArray>, dim: Int) {
        val tmp = File(file.parentFile, "${file.name}.tmp")
        val count = entries.size
        val regionBytes = count.toLong() * entrySize(dim)
        val totalBytes = (HeaderSize + regionBytes).toInt()
        RandomAccessFile(tmp, "rw").use { raf ->
            raf.setLength(totalBytes.toLong())
            val channel = raf.channel
            val buf = channel.map(FileChannel.MapMode.READ_WRITE, 0, totalBytes.toLong())
            buf.order(ByteOrder.LITTLE_ENDIAN)
            buf.putInt(Magic)
            buf.putInt(Version)
            buf.putInt(count)
            buf.putInt(dim)
            // Checksum placeholder; fill after the region is written.
            val checksumSlot = buf.position()
            buf.putInt(0)
            buf.putInt(0) // reserved
            for ((id, emb) in entries) {
                buf.putLong(id)
                buf.asFloatBuffer().put(emb)
                buf.position(buf.position() + dim * 4)
            }
            val checksum = crc32(buf, HeaderSize, regionBytes.toInt())
            buf.putInt(checksumSlot, checksum)
            buf.force()
            channel.force(true)
        }
        if (!tmp.renameTo(file)) {
            // Rename can fail across volumes / on some OEMs; fall back to copy.
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private fun crc32(buf: ByteBuffer, offset: Int, length: Int): Int {
        val crc = CRC32()
        val saved = buf.position()
        buf.position(offset)
        val limit = offset + length
        val temp = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, temp.size)
            buf.get(temp, 0, chunk)
            crc.update(temp, 0, chunk)
            remaining -= chunk
        }
        buf.position(saved)
        return crc.value.toInt()
    }

    /** Best-effort unmap — Android's MappedByteBuffer is freed by GC; this only nulls our ref. */
    private fun unmap(buf: MappedByteBuffer) {
        runCatching { buf.clear() }
    }

    private fun entrySize(dim: Int): Int = FaceIdBytes + dim * FloatBytes

    override fun close() {
        flush()
        lock.withLock {
            mapped?.let { unmap(it) }
            mapped = null
            idToIndex.clear()
        }
    }

    companion object {
        private const val Tag = "FaceVectorIndex"
        private const val FileName = "face_vector_index.bin"
        private const val Magic = 0x46564958 // "FVIX"
        private const val Version = 1
        const val EmbeddingDim = 512
        private const val FaceIdBytes = 8
        private const val FloatBytes = 4
        private const val HeaderSize = 24 // magic, version, count, dim, checksum, reserved
        private const val MaxDim = 4096
    }
}

private inline fun <T> ReentrantLock.withLock(action: () -> T): T {
    lock()
    try { return action() } finally { unlock() }
}
