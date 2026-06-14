package com.devomind.gallerysearch

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.InputStreamReader
import java.util.zip.GZIPInputStream

class WordNetExpansionDictionary(private val context: Context) {
    private var data: Map<String, Expansion> = emptyMap()
    val isAvailable: Boolean get() = data.isNotEmpty()
    val conceptCount: Int get() = data.size

    init {
        load()
    }

    data class Expansion(
        val synonyms: List<String>,
        val hypernyms: List<String>,
        val hyponyms: List<String>
    )

    private fun load() {
        try {
            val assetName = "photo_synonyms.json.gz"
            val inputStream = context.assets.open(assetName)
            val gzipStream = GZIPInputStream(inputStream)
            val reader = InputStreamReader(gzipStream, Charsets.UTF_8)
            val jsonString = reader.readText()
            val json = JSONObject(jsonString)

            val mutableData = mutableMapOf<String, Expansion>()
            json.keys().forEach { key ->
                val obj = json.getJSONObject(key)
                mutableData[key] = Expansion(
                    synonyms = obj.optJSONArray("syn")?.let { arr -> List(arr.length()) { i -> arr.getString(i) } } ?: emptyList(),
                    hypernyms = obj.optJSONArray("hyper")?.let { arr -> List(arr.length()) { i -> arr.getString(i) } } ?: emptyList(),
                    hyponyms = obj.optJSONArray("hypo")?.let { arr -> List(arr.length()) { i -> arr.getString(i) } } ?: emptyList()
                )
            }
            data = mutableData
            Log.d(Tag, "WordNet query expansion active: ${data.size} concepts")
        } catch (e: Exception) {
            Log.w(Tag, "WordNet not available: ${e.message}")
        }
    }

    fun lookup(term: String): Expansion? = data[term.lowercase()]

    companion object {
        private const val Tag = "WordNetDict"
        const val WeightOriginal = 1.00f
        const val WeightSynonym = 0.85f
        const val WeightHypernym = 0.60f
        const val WeightHyponym = 0.50f
    }
}
