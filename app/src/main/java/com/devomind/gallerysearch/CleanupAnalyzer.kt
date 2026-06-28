package com.devomind.gallerysearch

import android.net.Uri
import kotlinx.coroutines.yield

/**
 * Finds clutter the user probably wants to remove, using ONLY on-device signals:
 *  - existing MobileCLIP image embeddings (near-duplicate + similar grouping, zero-shot category guess)
 *  - cheap filename / folder heuristics (screenshots, stickers)
 *  - one downscaled-bitmap pass for blur / brightness (supplied by the caller as [ImageStats])
 *  - plain metadata (low resolution)
 *
 * No bitmaps are decoded here and no ONNX is touched directly; the host passes small callbacks so
 * this stays pure/testable. Everything produced is a *suggestion* — the UI reviews and confirms.
 */
object CleanupAnalyzer {

    enum class Category {
        DUPLICATES, SIMILAR,
        SCREENSHOTS, MEMES, STICKERS, DOCUMENTS, RECEIPTS, QR_CODES,
        BLURRY, DARK, BRIGHT, LOW_RESOLUTION
    }

    /** Per-image pixel statistics from a single downscaled decode. */
    data class ImageStats(val variance: Float, val meanLuma: Float, val fractionNearWhite: Float)

    data class Report(
        val categoryItems: Map<Category, List<GalleryRepository.MediaItem>>,
        val suggestedDeleteUris: Map<Category, Set<Uri>>,
        val sizeByUri: Map<String, Long>
    ) {
        fun count(category: Category): Int = categoryItems[category]?.size ?: 0

        fun isEmpty(): Boolean = Category.entries.all { count(it) == 0 }

        /** The set we'd delete in a category if the user accepts suggestions (falls back to all items). */
        private fun deletableUris(category: Category): Set<Uri> {
            val suggested = suggestedDeleteUris[category].orEmpty()
            if (suggested.isNotEmpty()) return suggested
            return categoryItems[category].orEmpty().mapTo(LinkedHashSet()) { it.uri }
        }

        fun reclaimableBytes(category: Category): Long =
            deletableUris(category).sumOf { sizeByUri[it.toString()] ?: 0L }

        /** Union of clutter candidates across all categories (de-duplicated). */
        private fun allClutterUris(): Set<Uri> {
            val all = LinkedHashSet<Uri>()
            for (c in Category.entries) all.addAll(deletableUris(c))
            return all
        }

        fun totalClutterCount(): Int = allClutterUris().size

        fun totalReclaimableBytes(): Long = allClutterUris().sumOf { sizeByUri[it.toString()] ?: 0L }
    }

    // --- Tunables (conservative — precision over recall, since deletes are destructive) ---
    private const val DUPLICATE_SIMILARITY = 0.97f      // near-identical
    private const val SIMILAR_SIMILARITY = 0.93f        // burst / lightly-edited / reposts
    private const val CLASS_MARGIN = 0.02f
    private const val CLASS_MIN_ABS = 0.17f
    private const val BLUR_VARIANCE_THRESHOLD = 60f
    private const val DARK_LUMA_THRESHOLD = 40f          // 0..255 mean luminance
    private const val BRIGHT_FRACTION_THRESHOLD = 0.55f  // share of near-white pixels
    private const val LOW_RES_LONG_EDGE = 640            // longest edge in px
    private const val MAX_DEDUP_ITEMS = 2000

    private val PHOTO_PROMPTS = listOf(
        "a photo", "a photograph", "a picture of a person", "a landscape photograph",
        "a photo of food", "a selfie", "a nature photo"
    )

    private enum class ClipClass(val category: Category?, val prompts: List<String>) {
        PHOTO(null, emptyList()),
        SCREENSHOT(Category.SCREENSHOTS, listOf(
            "a screenshot", "a screenshot of a phone screen", "a screenshot of an app",
            "a screenshot of a website", "a screenshot of a chat conversation"
        )),
        MEME(Category.MEMES, listOf(
            "a meme", "an internet meme", "a funny image with caption text", "a reaction image"
        )),
        STICKER(Category.STICKERS, listOf(
            "a sticker", "a whatsapp sticker", "a telegram sticker", "a cartoon sticker",
            "an emoji", "a smiley"
        )),
        DOCUMENT(Category.DOCUMENTS, listOf(
            "a document", "a scanned document", "a page of text", "a pdf page"
        )),
        RECEIPT(Category.RECEIPTS, listOf(
            "a receipt", "a store receipt", "a bill", "an invoice"
        )),
        QR(Category.QR_CODES, listOf(
            "a qr code", "a qr code for payment", "a barcode"
        ))
    }

    suspend fun analyze(
        items: List<GalleryRepository.MediaItem>,
        embeddings: Map<String, FloatArray>,
        sizeByUri: Map<String, Long>,
        encodeText: (String) -> FloatArray?,
        imageStats: (Uri) -> ImageStats?,
        onProgress: (done: Int, total: Int) -> Unit,
        onPartial: (Report) -> Unit = {}
    ): Report {
        val categoryItems = linkedMapOf<Category, MutableList<GalleryRepository.MediaItem>>()
        val suggested = linkedMapOf<Category, MutableSet<Uri>>()
        for (c in Category.entries) {
            categoryItems[c] = mutableListOf()
            suggested[c] = linkedSetOf()
        }

        // 1) Duplicates (>=0.97) and Similar (>=0.93) from existing embeddings, in one pairwise pass.
        val dedupItems = items.filter { embeddings.containsKey(it.uri.toString()) }.take(MAX_DEDUP_ITEMS)
        val (dupGroups, simGroups) = groupBySimilarity(dedupItems, embeddings)
        val duplicateUris = HashSet<String>()
        for (group in dupGroups) {
            val keep = group.maxByOrNull { sizeByUri[it.uri.toString()] ?: 0L } ?: continue
            for (item in group) {
                duplicateUris.add(item.uri.toString())
                categoryItems[Category.DUPLICATES]!!.add(item)
                if (item.uri != keep.uri) suggested[Category.DUPLICATES]!!.add(item.uri)
            }
        }
        for (group in simGroups) {
            // Drop members that are already exact duplicates; keep genuine "similar but not identical".
            val remaining = group.filter { it.uri.toString() !in duplicateUris }
            if (remaining.size < 2) continue
            val keep = remaining.maxByOrNull { sizeByUri[it.uri.toString()] ?: 0L } ?: continue
            for (item in remaining) {
                categoryItems[Category.SIMILAR]!!.add(item)
                if (item.uri != keep.uri) suggested[Category.SIMILAR]!!.add(item.uri)
            }
        }

        // 2) Zero-shot class vectors.
        val photoVec = averagedPromptVector(PHOTO_PROMPTS, encodeText)
        val classVecs = ClipClass.entries.filter { it != ClipClass.PHOTO }
            .associateWith { averagedPromptVector(it.prompts, encodeText) }

        // Classify each item into at most one content category; collect "real photos" for the pixel pass.
        val photoCandidates = mutableListOf<GalleryRepository.MediaItem>()
        for (item in items) {
            val embedding = embeddings[item.uri.toString()]
            val forcedClass = when {
                looksLikeScreenshot(item) -> ClipClass.SCREENSHOT
                looksLikeSticker(item) -> ClipClass.STICKER
                else -> null
            }

            val cls = forcedClass ?: classifyByClip(embedding, photoVec, classVecs)
            val category = cls?.category
            if (category != null) {
                categoryItems[category]!!.add(item)
                // Stickers and memes are almost always disposable — pre-select them.
                if (category == Category.STICKERS || category == Category.MEMES) {
                    suggested[category]!!.add(item.uri)
                }
            } else {
                photoCandidates.add(item)
            }
        }

        // 3) Single decode pass over real photos → blur / dark / bright; low-res from metadata.
        fun snapshot() = Report(
            categoryItems = categoryItems.mapValues { it.value.toList() },
            suggestedDeleteUris = suggested.mapValues { it.value.toSet() },
            sizeByUri = sizeByUri
        )

        // Show the fast (embedding + metadata) categories immediately so the screen is live.
        onPartial(snapshot())

        // Bounded-free: scan every real photo (worker runs this in the background). Most-recent
        // first so streamed/partial results surface the freshest photos earliest.
        val scanList = photoCandidates.sortedByDescending { it.dateMillis }
        val total = scanList.size
        var done = 0
        onProgress(0, total)
        for (item in scanList) {
            val stats = imageStats(item.uri)
            when {
                stats != null && stats.variance < BLUR_VARIANCE_THRESHOLD -> {
                    categoryItems[Category.BLURRY]!!.add(item)
                    suggested[Category.BLURRY]!!.add(item.uri)
                }
                stats != null && stats.meanLuma < DARK_LUMA_THRESHOLD ->
                    categoryItems[Category.DARK]!!.add(item)
                stats != null && stats.fractionNearWhite > BRIGHT_FRACTION_THRESHOLD ->
                    categoryItems[Category.BRIGHT]!!.add(item)
                isLowResolution(item) ->
                    categoryItems[Category.LOW_RESOLUTION]!!.add(item)
            }
            done++
            if (done % 40 == 0 || done == total) {
                onProgress(done, total)
                onPartial(snapshot())
                yield()
            }
        }

        return snapshot()
    }

    private fun classifyByClip(
        embedding: FloatArray?,
        photoVec: FloatArray?,
        classVecs: Map<ClipClass, FloatArray?>
    ): ClipClass? {
        if (embedding == null || photoVec == null) return null
        val photoScore = EmbeddingUtils.cosineSimilarity(embedding, photoVec)
        var bestClass: ClipClass? = null
        var bestScore = -1f
        for ((cls, vec) in classVecs) {
            if (vec == null) continue
            val score = EmbeddingUtils.cosineSimilarity(embedding, vec)
            if (score > bestScore) {
                bestScore = score
                bestClass = cls
            }
        }
        if (bestClass == null) return null
        return if (bestScore >= CLASS_MIN_ABS && bestScore - photoScore >= CLASS_MARGIN) bestClass else null
    }

    /** One pairwise pass producing both duplicate (>=0.97) and similar (>=0.93) groupings. */
    private suspend fun groupBySimilarity(
        items: List<GalleryRepository.MediaItem>,
        embeddings: Map<String, FloatArray>
    ): Pair<List<List<GalleryRepository.MediaItem>>, List<List<GalleryRepository.MediaItem>>> {
        val n = items.size
        if (n < 2) return emptyList<List<GalleryRepository.MediaItem>>() to emptyList()
        val vecs = items.map { embeddings[it.uri.toString()]!! }
        val dupParent = IntArray(n) { it }
        val simParent = IntArray(n) { it }

        fun find(parent: IntArray, x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
            return r
        }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                val sim = EmbeddingUtils.cosineSimilarity(vecs[i], vecs[j])
                if (sim >= SIMILAR_SIMILARITY) {
                    simParent[find(simParent, i)] = find(simParent, j)
                    if (sim >= DUPLICATE_SIMILARITY) {
                        dupParent[find(dupParent, i)] = find(dupParent, j)
                    }
                }
            }
            if (i % 64 == 0) yield()
        }

        return bucketize(items, dupParent, ::find) to bucketize(items, simParent, ::find)
    }

    private fun bucketize(
        items: List<GalleryRepository.MediaItem>,
        parent: IntArray,
        find: (IntArray, Int) -> Int
    ): List<List<GalleryRepository.MediaItem>> {
        val buckets = LinkedHashMap<Int, MutableList<GalleryRepository.MediaItem>>()
        for (i in items.indices) {
            buckets.getOrPut(find(parent, i)) { mutableListOf() }.add(items[i])
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

    private fun isLowResolution(item: GalleryRepository.MediaItem): Boolean {
        if (item.width <= 0 || item.height <= 0) return false
        return maxOf(item.width, item.height) < LOW_RES_LONG_EDGE
    }

    private fun looksLikeScreenshot(item: GalleryRepository.MediaItem): Boolean {
        val name = item.displayName?.lowercase().orEmpty()
        val path = item.path.lowercase()
        return name.startsWith("screenshot") || name.startsWith("screen shot") ||
            path.contains("/screenshots/") || path.contains("screenshot")
    }

    private fun looksLikeSticker(item: GalleryRepository.MediaItem): Boolean {
        val name = item.displayName?.lowercase().orEmpty()
        val path = item.path.lowercase()
        return path.contains("/stickers/") || path.contains("sticker") ||
            path.contains("/whatsapp stickers") || name.startsWith("sticker")
    }
}
