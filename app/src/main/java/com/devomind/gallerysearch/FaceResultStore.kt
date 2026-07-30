package com.devomind.gallerysearch

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Private, lightweight cache of photos already inspected by YuNet and their face counts. */
class FaceResultStore(context: Context) {
    private val file = File(context.filesDir, FileName)

    data class Snapshot(val scannedUris: Set<String>, val faceCounts: Map<String, Int>) {
        val totalFaces: Int get() = faceCounts.values.sum()
    }

    @Synchronized
    fun load(validUris: Set<String>? = null): Snapshot {
        val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return Snapshot(emptySet(), emptyMap())
        // Older stores were written by a detector that silently found nothing; drop them so
        // every candidate is rescanned once instead of staying marked "scanned: 0 faces".
        if (root.optInt("version", 0) < Version) return Snapshot(emptySet(), emptyMap())
        val allowed = validUris
        val scanned = root.optJSONArray("scanned").toStringSet()
            .filterTo(LinkedHashSet()) { uri -> allowed == null || uri in allowed }
        val faces = root.optJSONObject("faces")
            ?.keys()
            ?.asSequence()
            ?.mapNotNull { uri ->
                val count = root.optJSONObject("faces")?.optInt(uri, 0) ?: 0
                uri.takeIf { count > 0 && (allowed == null || it in allowed) }?.let { it to count }
            }
            ?.toMap(LinkedHashMap())
            .orEmpty()
        return Snapshot(scanned, faces)
    }

    @Synchronized
    fun merge(results: Map<String, Int>, validUris: Set<String>) {
        val previous = load(validUris)
        val scanned = (previous.scannedUris + results.keys).filterTo(LinkedHashSet()) { it in validUris }
        val faces = previous.faceCounts.toMutableMap().apply {
            results.forEach { (uri, count) ->
                if (count > 0) put(uri, count) else remove(uri)
            }
            keys.retainAll(validUris)
        }
        val root = JSONObject().apply {
            put("version", Version)
            put("scanned", JSONArray(scanned.toList()))
            put("faces", JSONObject().apply { faces.forEach { (uri, count) -> put(uri, count) } })
        }
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(root.toString())
        if (!temp.renameTo(file)) {
            file.writeText(root.toString())
            temp.delete()
        }
    }

    private fun JSONArray?.toStringSet(): Set<String> {
        if (this == null) return emptySet()
        return buildSet {
            for (index in 0 until length()) optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }

    private companion object {
        const val FileName = "face_detection_index.json"
        const val Version = 2
    }
}
