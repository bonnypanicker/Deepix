package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persists Smart Cleanup results so the dedicated screen can show them instantly and update live
 * while [CleanupWorker] keeps scanning in the background (in parallel with indexing).
 *
 * Only uri strings are stored (per category + the suggested-delete subset); the UI resolves these
 * to media items and sizes itself, keeping the file small.
 */
class CleanupResultStore(context: Context) {

    private val file = File(context.filesDir, FILE_NAME)

    data class Result(
        val categoryUris: Map<CleanupAnalyzer.Category, List<String>>,
        val suggestedUris: Map<CleanupAnalyzer.Category, List<String>>,
        val done: Int,
        val total: Int,
        val complete: Boolean,
        val updatedAt: Long
    )

    fun save(result: Result) {
        runCatching {
            val root = JSONObject()
            root.put("updatedAt", result.updatedAt)
            root.put("done", result.done)
            root.put("total", result.total)
            root.put("complete", result.complete)

            val categories = JSONObject()
            val suggested = JSONObject()
            for (category in CleanupAnalyzer.Category.entries) {
                categories.put(category.name, JSONArray(result.categoryUris[category].orEmpty()))
                suggested.put(category.name, JSONArray(result.suggestedUris[category].orEmpty()))
            }
            root.put("categories", categories)
            root.put("suggested", suggested)

            val tmp = File(file.parentFile, "$FILE_NAME.tmp")
            tmp.writeText(root.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString())
                tmp.delete()
            }
        }.onFailure { Log.w(TAG, "Failed to save cleanup results.", it) }
    }

    fun load(): Result? {
        if (!file.exists()) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val categories = root.optJSONObject("categories") ?: JSONObject()
            val suggested = root.optJSONObject("suggested") ?: JSONObject()
            val categoryUris = LinkedHashMap<CleanupAnalyzer.Category, List<String>>()
            val suggestedUris = LinkedHashMap<CleanupAnalyzer.Category, List<String>>()
            for (category in CleanupAnalyzer.Category.entries) {
                categoryUris[category] = categories.optJSONArray(category.name).toStringList()
                suggestedUris[category] = suggested.optJSONArray(category.name).toStringList()
            }
            Result(
                categoryUris = categoryUris,
                suggestedUris = suggestedUris,
                done = root.optInt("done", 0),
                total = root.optInt("total", 0),
                complete = root.optBoolean("complete", false),
                updatedAt = root.optLong("updatedAt", 0L)
            )
        }.onFailure { Log.w(TAG, "Failed to load cleanup results.", it) }.getOrNull()
    }

    fun clear() {
        runCatching { if (file.exists()) file.delete() }
    }

    private fun JSONArray?.toStringList(): List<String> {
        if (this == null) return emptyList()
        val list = ArrayList<String>(length())
        for (i in 0 until length()) list.add(optString(i))
        return list
    }

    companion object {
        private const val TAG = "CleanupResultStore"
        private const val FILE_NAME = "cleanup_results.json"
    }
}
