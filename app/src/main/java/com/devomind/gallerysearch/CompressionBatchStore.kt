package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists the active compression batch so [CompressionWorker] can run it in the background and
 * resume after process death: WorkManager restarts the worker, the worker reloads this file and
 * continues where it left off. The staged files themselves live in [CompressionEngine]'s journal —
 * this store only tracks batch intent (which photos, which preset, which phase, per-item verdicts)
 * and the results the UI reports back to Smart Cleanup.
 *
 * Writes are atomic (tmp + rename) and every mutation is guarded by the batch id it was loaded
 * with, so a stale worker from a cancelled/replaced batch can never overwrite a newer one.
 */
class CompressionBatchStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)
    private val lock = Any()

    enum class Phase { PREPARING, AWAITING_DECISION, COMMITTING, DONE, CANCELLED }

    enum class CommitMode { REPLACE, COPY }

    enum class ItemStatus { PENDING, PREPARING, READY, NO_GAIN, FAILED, DONE }

    data class Item(
        val uri: String,
        var displayName: String,
        var mimeType: String,
        var sizeBytes: Long,
        var status: ItemStatus = ItemStatus.PENDING,
        var note: String = "",
        var entryId: String = "",
        var sizeBefore: Long = 0L,
        var sizeAfter: Long = 0L,
        var format: String = "",
        var destPath: String = "",
        var originalPath: String = ""
    )

    data class Batch(
        val id: String,
        var phase: Phase = Phase.PREPARING,
        var quality: Int = 80,
        var maxDimension: Int = 8192,
        var commitMode: CommitMode? = null,
        val items: MutableList<Item> = mutableListOf(),
        var replacedUris: MutableList<String> = mutableListOf(),
        var copiedUris: MutableList<String> = mutableListOf(),
        var savedBytes: Long = 0L,
        var updatedAt: Long = System.currentTimeMillis()
    ) {
        val isActive: Boolean get() = phase == Phase.PREPARING || phase == Phase.AWAITING_DECISION || phase == Phase.COMMITTING
    }

    fun load(): Batch? = synchronized(lock) {
        if (!file.exists()) return null
        runCatching {
            val root = JSONObject(file.readText())
            val itemsArray = root.optJSONArray("items") ?: JSONArray()
            val items = (0 until itemsArray.length()).map { i ->
                val o = itemsArray.getJSONObject(i)
                Item(
                    uri = o.getString("uri"),
                    displayName = o.optString("displayName", ""),
                    mimeType = o.optString("mimeType", ""),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    status = ItemStatus.valueOf(o.optString("status", ItemStatus.PENDING.name)),
                    note = o.optString("note", ""),
                    entryId = o.optString("entryId", ""),
                    sizeBefore = o.optLong("sizeBefore", 0L),
                    sizeAfter = o.optLong("sizeAfter", 0L),
                    format = o.optString("format", ""),
                    destPath = o.optString("destPath", ""),
                    originalPath = o.optString("originalPath", "")
                )
            }.toMutableList()
            Batch(
                id = root.getString("id"),
                phase = Phase.valueOf(root.optString("phase", Phase.PREPARING.name)),
                quality = root.optInt("quality", 80),
                maxDimension = root.optInt("maxDimension", 8192),
                commitMode = root.optString("commitMode", "").takeIf { it.isNotBlank() }
                    ?.let { CommitMode.valueOf(it) },
                items = items,
                replacedUris = root.optJSONArray("replacedUris").toStringList(),
                copiedUris = root.optJSONArray("copiedUris").toStringList(),
                savedBytes = root.optLong("savedBytes", 0L),
                updatedAt = root.optLong("updatedAt", 0L)
            )
        }.onFailure { Log.w(TAG, "Failed to read compression batch.", it) }.getOrNull()
    }

    fun save(batch: Batch) = synchronized(lock) {
        runCatching {
            batch.updatedAt = System.currentTimeMillis()
            val root = JSONObject().apply {
                put("id", batch.id)
                put("phase", batch.phase.name)
                put("quality", batch.quality)
                put("maxDimension", batch.maxDimension)
                put("commitMode", batch.commitMode?.name ?: "")
                put("savedBytes", batch.savedBytes)
                put("updatedAt", batch.updatedAt)
                put("replacedUris", JSONArray(batch.replacedUris))
                put("copiedUris", JSONArray(batch.copiedUris))
                put("items", JSONArray().apply {
                    for (item in batch.items) {
                        put(JSONObject().apply {
                            put("uri", item.uri)
                            put("displayName", item.displayName)
                            put("mimeType", item.mimeType)
                            put("sizeBytes", item.sizeBytes)
                            put("status", item.status.name)
                            put("note", item.note)
                            put("entryId", item.entryId)
                            put("sizeBefore", item.sizeBefore)
                            put("sizeAfter", item.sizeAfter)
                            put("format", item.format)
                            put("destPath", item.destPath)
                            put("originalPath", item.originalPath)
                        })
                    }
                })
            }
            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Failed to save compression batch.", it) }
    }

    /**
     * Mutates the persisted batch only while [batchId] is still the stored one. Returns false when
     * the batch was replaced or cleared underneath the caller (stale worker), which must then stop.
     */
    fun update(batchId: String, mutate: (Batch) -> Unit): Boolean = synchronized(lock) {
        val current = load() ?: return false
        if (current.id != batchId) return false
        mutate(current)
        save(current)
        true
    }

    fun clear() = synchronized(lock) {
        runCatching { if (file.exists()) file.delete() }
    }

    private fun JSONArray?.toStringList(): MutableList<String> {
        if (this == null) return mutableListOf()
        val list = ArrayList<String>(length())
        for (i in 0 until length()) list.add(optString(i))
        return list
    }

    companion object {
        private const val TAG = "CompressionBatchStore"
        private const val FILE_NAME = "compression_batch.json"

        /** How long a batch parked at AWAITING_DECISION (staged, undecided) survives before
         *  startup sweeps it: staged copies are full-size photo files, so they can't sit forever. */
        const val StaleAwaitingMs = 7L * 24 * 60 * 60 * 1000L

        fun newBatchId(): String = "batch_${System.currentTimeMillis()}_${System.nanoTime()}"

        /**
         * True while a compression batch is mid-flight (preparing, awaiting the user's keep
         * decision, or committing). CLIP indexing and Smart Cleanup both yield to compression:
         * they retry cheaply and resume from persisted progress once the batch settles.
         */
        fun isCompressionActive(context: Context): Boolean =
            runCatching { CompressionBatchStore(context).load()?.isActive == true }.getOrDefault(false)
    }
}

/** Thrown by background jobs that yield mid-run while a compression batch owns the device. */
class CompressionRunningException : RuntimeException()
