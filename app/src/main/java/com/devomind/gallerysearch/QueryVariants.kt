package com.devomind.gallerysearch

/**
 * The caption phrasings one search query is encoded as.
 *
 * The text model was trained on captions, so a bare phrase is tried in a few shapes and the best
 * score per photo wins. The shapes are de-duplicated *after* the caption prefix is applied, because
 * the tokenizer adds `"a photo of "` to anything that lacks it: "blue shirt person" and "a photo of
 * blue shirt person" used to collapse into the same token sequence and be encoded twice per search,
 * returning the identical embedding both times.
 */
object QueryVariants {

    /** @return between one and three distinct phrasings; never two that encode to the same text. */
    fun build(query: String): List<String> {
        val cleaned = query.trim().replace(Regex("""\s+"""), " ")
        if (cleaned.isBlank()) return listOf(query)
        return listOf(
            cleaned,
            "a photo of $cleaned",
            "a picture of $cleaned",
            "$cleaned photo"
        )
            .map(TextTokenizer::withPhotoPrefix)
            .distinct()
    }
}
