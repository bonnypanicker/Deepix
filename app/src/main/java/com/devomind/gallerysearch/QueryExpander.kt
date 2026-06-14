package com.devomind.gallerysearch

import android.util.Log

class QueryExpander(
    private val dict: WordNetExpansionDictionary,
    private val textEncoder: TextEncoder
) {
    private val embeddingCache = mutableMapOf<String, FloatArray>()

    fun buildWeightedEmbedding(query: String): FloatArray {
        val terms = tokenize(query)
        if (terms.isEmpty()) return textEncoder.encode(query)

        val weightedTerms = mutableMapOf<String, Float>()

        for (term in terms) {
            weightedTerms[term] = WordNetExpansionDictionary.WeightOriginal
            val expansion = dict.lookup(term) ?: continue

            expansion.synonyms.forEach { syn ->
                weightedTerms[syn] = maxOf(weightedTerms[syn] ?: 0f, WordNetExpansionDictionary.WeightSynonym)
            }
            expansion.hypernyms.forEach { hyper ->
                weightedTerms[hyper] = maxOf(weightedTerms[hyper] ?: 0f, WordNetExpansionDictionary.WeightHypernym)
            }
            expansion.hyponyms.forEach { hypo ->
                weightedTerms[hypo] = maxOf(weightedTerms[hypo] ?: 0f, WordNetExpansionDictionary.WeightHyponym)
            }
        }

        var accumulated: FloatArray? = null
        var totalWeight = 0f

        for ((term, weight) in weightedTerms) {
            try {
                val embedding = getEmbedding(term)
                if (accumulated == null) {
                    accumulated = FloatArray(embedding.size) { i -> embedding[i] * weight }
                } else {
                    for (i in accumulated.indices) {
                        accumulated[i] += embedding[i] * weight
                    }
                }
                totalWeight += weight
            } catch (e: Exception) {
                Log.w(Tag, "Failed to encode term: $term")
            }
        }

        return if (accumulated != null && totalWeight > 0f) {
            EmbeddingUtils.l2Normalize(accumulated)
        } else {
            textEncoder.encode(query)
        }
    }

    private fun getEmbedding(term: String): FloatArray {
        return embeddingCache.getOrPut(term) { textEncoder.encode(term) }
    }

    private fun tokenize(query: String): List<String> {
        return query.lowercase()
            .replace(Regex("[^a-z0-9 ]"), " ")
            .split(" ")
            .filter { it.isNotBlank() && it.length > 2 }
    }

    companion object {
        private const val Tag = "QueryExpander"
    }
}
