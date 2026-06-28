package com.devomind.gallerysearch

import android.net.Uri
import kotlinx.coroutines.yield

/**
 * Finds clutter the user probably wants to remove, using ONLY signals available on-device:
 *  - existing MobileCLIP image embeddings (near-duplicate grouping + zero-shot category guess)
 *  - cheap filename / folder heuristics (screenshots, stickers)
 *  - Laplacian variance on a downscaled bitmap (blur), supplied by the caller
 *
 * Nothing here decodes bitmaps or touches ONNX directly; the host passes in small callbacks so
 * this stays pure/testable. All results are *suggestions* — the UI reviews and confirms.
 */
object CleanupAnalyzer {

    enum class Category { DUPLICATES, BLURRY, SCREENSHOTS, MEMES_STICKERS }

    data class Report(
        val categoryItems: Map<Category, List<GalleryRepository.MediaItem>>,
        val suggestedDeleteUris: Map<Category, Set<Uri>>,
        val sizeByUri: Map<String, Long>
    ) {
        fun count(category: Category): Int = categoryItems[category]?.size ?: 0

        fun isEmpty(): Boolean = Category.entries.all { count(it) == 0 }

        /** Bytes freed if every suggested item in [category] is deleted. */
        fun reclaimableBytes(category: Category): Long =
            suggestedDeleteUris[category].orEmpty().sumOf { sizeByUri[it.toString()] ?: 0L }
    }

    // --- Tunables (kept conservative — precision over recall, since deletes are destructive) ---
    private const val DUPLICATE_SIMILARITY = 0.94f       // cosine over L2-normalized embeddings
    private const val CLASS_MARGIN = 0.02f               // how much a class must beat "photo"
    private const val CLASS_MIN_ABS = 0.17f              // absolute floor for a class match
    private const val BLUR_VARIANCE_THRESHOLD = 60f      // lower = blurrier (on ~128px grayscale)
    private const val MAX_DEDUP_ITEMS = 2000             // bound the O(n^2) duplicate pass

    private val PHOTO_PROMPTS = listOf(
        "a photo", "a photograph", "a picture of a person",
        "a landscape photograph", "a photo of food", "a selfie"
    )
    private val SCREENSHOT_PROMPTS = listOf(
        "a screenshot", "a screenshot of a phone screen", "a screenshot of an app interface",
        "a screenshot of a web page", "a screenshot of a chat conversation"
    )
    private val MEME_PROMPTS = listOf(
        "a meme", "a sticker", "a funny meme with caption text", "a cartoon sticker", "an emoji"
    )

    suspend fun analyze(
        items: List<GalleryRepository.MediaItem>,
        embeddings: Map<String, FloatArray>,
        sizeByUri: Map<String, Long>,
        encodeText: (String) -> FloatArray?,
        blurVariance: (Uri) -> Float?,
        onProgress: (done: Int, total: Int) -> Unit
    ): Report {
        val categoryItems = linkedMapOf<Category, MutableList<GalleryRepository.MediaItem>>()
        val suggested = linkedMapOf<Category, MutableSet<Uri>>()
        for (c in Category.entries) {
            categoryItems[c] = mutableListOf()
            suggested[c] = linkedSetOf()
        }

        // 1) Near-duplicates from existing embeddings (fast, no decode).
        val dedupItems = items.filter { embeddings.containsKey(it.uri.toString()) }.take(MAX_DEDUP_ITEMS)
        val groups = groupDuplicates(dedupItems, embeddings)
        for (group in groups) {
            // Keep the largest file (usually the highest-quality original); suggest deleting the rest.
            val keep = group.maxByOrNull { sizeByUri[it.uri.toString()] ?: 0L } ?: continue
            categoryItems[Category.DUPLICATES]!!.add(keep)
            for (item in group) {
                if (item.uri == keep.uri) continue
                categoryItems[Category.DUPLICATES]!!.add(item)
                suggested[Category.DUPLICATES]!!.add(item.uri)
            }
        }

        // 2) Zero-shot category prompt vectors (averaged + normalized per class).
        val photoVec = averagedPromptVector(PHOTO_PROMPTS, encodeText)
        val shotVec = averagedPromptVector(SCREENSHOT_PROMPTS, encodeText)
        val memeVec = averagedPromptVector(MEME_PROMPTS, encodeText)

        // Classify every item; collect the "real photos" that are worth a blur scan.
        val photoCandidates = mutableListOf<GalleryRepository.MediaItem>()
        for (item in items) {
            val metaShot = looksLikeScreenshot(item)
            val metaSticker = looksLikeSticker(item)
            val embedding = embeddings[item.uri.toString()]

            var clsShot = false
            var clsMeme = false
            if (embedding != null && photoVec != null) {
                val sPhoto = EmbeddingUtils.cosineSimilarity(embedding, photoVec)
                val sShot = shotVec?.let { EmbeddingUtils.cosineSimilarity(embedding, it) } ?: -1f
                val sMeme = memeVec?.let { EmbeddingUtils.cosineSimilarity(embedding, it) } ?: -1f
                clsShot = sShot >= CLASS_MIN_ABS && sShot - sPhoto >= CLASS_MARGIN && sShot >= sMeme
                clsMeme = sMeme >= CLASS_MIN_ABS && sMeme - sPhoto >= CLASS_MARGIN && sMeme > sShot
            }

            when {
                metaShot || clsShot -> categoryItems[Category.SCREENSHOTS]!!.add(item)
                metaSticker || clsMeme -> {
                    categoryItems[Category.MEMES_STICKERS]!!.add(item)
                    // Stickers/emoji are almost always disposable clutter — pre-select them.
                    suggested[Category.MEMES_STICKERS]!!.add(item.uri)
                }
                else -> photoCandidates.add(item)
            }
        }

        // 3) Blur scan (the expensive, decode-bound pass) — photos only, with progress.
        val total = photoCandidates.size
        var done = 0
        onProgress(0, total)
        for (item in photoCandidates) {
            val variance = blurVariance(item.uri)
            if (variance != null && variance < BLUR_VARIANCE_THRESHOLD) {
                categoryItems[Category.BLURRY]!!.add(item)
                suggested[Category.BLURRY]!!.add(item.uri)
            }
            done++
            if (done % 8 == 0 || done == total) {
                onProgress(done, total)
                yield()
            }
        }

        return Report(
            categoryItems = categoryItems.mapValues { it.value.toList() },
            suggestedDeleteUris = suggested.mapValues { it.value.toSet() },
            sizeByUri = sizeByUri
        )
    }

    /** Union-find grouping of items whose embeddings exceed [DUPLICATE_SIMILARITY]. */
    private suspend fun groupDuplicates(
        items: List<GalleryRepository.MediaItem>,
        embeddings: Map<String, FloatArray>
    ): List<List<GalleryRepository.MediaItem>> {
        val n = items.size
        if (n < 2) return emptyList()
        val vecs = items.map { embeddings[it.uri.toString()]!! }
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (EmbeddingUtils.cosineSimilarity(vecs[i], vecs[j]) >= DUPLICATE_SIMILARITY) {
                    parent[find(i)] = find(j)
                }
            }
            if (i % 64 == 0) yield()
        }

        val buckets = LinkedHashMap<Int, MutableList<GalleryRepository.MediaItem>>()
        for (i in 0 until n) {
            buckets.getOrPut(find(i)) { mutableListOf() }.add(items[i])
        }
        return buckets.values.filter { it.size >= 2 }
    }

    private fun averagedPromptVector(prompts: List<String>, encodeText: (String) -> FloatArray?): FloatArray? {
        var acc: FloatArray? = null
        var n = 0
        for (p in prompts) {
            val v = encodeText(p) ?: continue
            if (acc == null) acc = FloatArray(v.size)
            val a = acc
            val len = minOf(a.size, v.size)
            for (k in 0 until len) a[k] += v[k]
            n++
        }
        if (acc == null || n == 0) return null
        return EmbeddingUtils.l2Normalize(acc)
    }

    private fun looksLikeScreenshot(item: GalleryRepository.MediaItem): Boolean {
        val name = item.displayName?.lowercase().orEmpty()
        val path = item.path.lowercase()
        return name.startsWith("screenshot") || name.startsWith("screen shot") ||
            name.startsWith("screenshot_") || path.contains("/screenshots/") ||
            path.contains("screenshot")
    }

    private fun looksLikeSticker(item: GalleryRepository.MediaItem): Boolean {
        val name = item.displayName?.lowercase().orEmpty()
        val path = item.path.lowercase()
        return path.contains("/stickers/") || path.contains("sticker") ||
            path.contains("/whatsapp stickers") || name.startsWith("sticker") ||
            name.contains("meme") || path.contains("/meme")
    }
}
