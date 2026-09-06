package com.devomind.gallerysearch

import android.net.Uri
import kotlinx.coroutines.yield

/**
 * Finds clutter the user probably wants to remove, using ONLY on-device signals:
 *  - existing MobileCLIP image embeddings (near-duplicate + burst + similar grouping, zero-shot
 *    category guess, sensitive-content screen)
 *  - cheap filename / folder heuristics (screenshots, stickers)
 *  - one downscaled-bitmap pass for blur / brightness (supplied by the caller as [ImageStats])
 *  - plain metadata (low resolution, capture time for burst clustering)
 *
 * No bitmaps are decoded here and no ONNX is touched directly; the host passes small callbacks so
 * this stays pure/testable. Everything produced is a *suggestion* — the UI reviews and confirms.
 */
object CleanupAnalyzer {

    enum class Category {
        DUPLICATES, SIMILAR, BURSTS,
        LIKELY_CLUTTER, SCREENSHOTS, DOCUMENTS, RECEIPTS, QR_CODES, NSFW,
        BLURRY, DARK, BRIGHT, LOW_RESOLUTION,
        COMPRESSIBLE
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
    // Sensitive-content screen: far looser than the blur feature's NsfwClassifier.SENSITIVE_MARGIN
    // (0.05) on purpose — cleanup items are surfaced for human review, never auto-hidden or
    // pre-selected, so recall ("let more suspecting photos in") matters more than false positives.
    // Calibration puts safe photos at median margin -0.012, so 0.02 flags everything leaning
    // sensitive while still requiring the image to sit closer to sensitive than to safe.
    private const val NSFW_MARGIN = 0.02f
    // Bursts: looser than SIMILAR (0.93) and even than the image-search floor (0.55) because the
    // 60-second capture-time gate does the real filtering — within one minute, shots are almost
    // always the same moment, so cosine only needs to confirm scene-level resemblance.
    private const val BURST_SIMILARITY = 0.82f
    private const val BURST_TIME_GAP_MS = 60_000L        // consecutive shots ≤1 min apart = one moment
    private const val BURST_MIN_PHOTOS = 2               // any same-moment pair is worth a review
    private const val MAX_BURST_CLUSTER = 600            // skip timelapse-style runs (pairwise cost)

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
        MEME(Category.LIKELY_CLUTTER, listOf(
            "a meme", "an internet meme", "a funny image with caption text", "a reaction image"
        )),
        STICKER(Category.LIKELY_CLUTTER, listOf(
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
        onPartial: (Report) -> Unit = {},
        resumeQuality: Map<Category, Set<String>> = emptyMap(),
        scannedUris: MutableSet<String> = mutableSetOf(),
        nsfwMargin: (FloatArray) -> Float? = { null }
    ): Report {
        val categoryItems = linkedMapOf<Category, MutableList<GalleryRepository.MediaItem>>()
        val suggested = linkedMapOf<Category, MutableSet<Uri>>()
        for (c in Category.entries) {
            categoryItems[c] = mutableListOf()
            suggested[c] = linkedSetOf()
        }

        // 0) Compression candidates: large JPEG/PNG/WebP/BMP stills that HEIC shrinks a lot.
        //    Metadata-only (instant). Overlaps the other categories on purpose — a duplicate can
        //    also be worth compressing. All candidates are pre-selected as the recommendation.
        items.asSequence()
            .filter { it.mediaType == GalleryRepository.MediaType.Image }
            .filter { CompressionEngine.isCompressibleMime(it.mimeType) }
            .map { item -> item to (sizeByUri[item.uri.toString()] ?: item.sizeBytes) }
            .filter { (_, size) -> size >= CompressionEngine.MIN_COMPRESSIBLE_BYTES }
            .sortedByDescending { (item, size) -> CompressionEngine.estimatedSavings(item.mimeType, size) }
            .forEach { (item, _) ->
                categoryItems[Category.COMPRESSIBLE]!!.add(item)
                suggested[Category.COMPRESSIBLE]!!.add(item.uri)
            }

        // 1) Bursts first: time-clustered sequences (capture time) with moderately similar content.
        //    Runs over EVERY embedded photo — image search finds these with plain cosine lookups, so
        //    burst detection must not inherit the dedup pass's item cap — and claims its members
        //    before the near-identical passes. (When this ran after dup/sim, bursts with >=0.93
        //    similarity were swallowed by SIMILAR and the Bursts tile stayed empty.)
        val embeddedItems = items.filter { embeddings.containsKey(it.uri.toString()) }
        val burstGroups = groupBursts(embeddedItems, embeddings)
        val burstUris = HashSet<String>()
        for (group in burstGroups) {
            for (item in group) burstUris.add(item.uri.toString())
            // The kept shot leads its group in the flat category list; SmartCleanupActivity splits
            // the list back into per-burst sections at these kept items when rendering headers.
            val keep = group.maxByOrNull { sizeByUri[it.uri.toString()] ?: 0L } ?: continue
            categoryItems[Category.BURSTS]!!.add(keep)
            for (item in group) {
                if (item.uri == keep.uri) continue
                categoryItems[Category.BURSTS]!!.add(item)
                suggested[Category.BURSTS]!!.add(item.uri)
            }
        }

        // 2) Duplicates (>=0.97) and Similar (>=0.93) over the non-burst remainder, in one pairwise
        //    pass. Cross-time near-identical copies (edited copies, re-downloads, reposts) land
        //    here; same-moment sequences are Bursts' job now.
        val dedupItems = embeddedItems
            .filter { it.uri.toString() !in burstUris }
            .take(MAX_DEDUP_ITEMS)
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

        // 3) Zero-shot class vectors.
        val photoVec = averagedPromptVector(PHOTO_PROMPTS, encodeText)
        val classVecs = ClipClass.entries.filter { it != ClipClass.PHOTO }
            .associateWith { averagedPromptVector(it.prompts, encodeText) }

        // Classify each item into at most one content category; collect "real photos" for the pixel pass.
        val photoCandidates = mutableListOf<GalleryRepository.MediaItem>()
        for (item in items) {
            val embedding = embeddings[item.uri.toString()]
            // Sensitive-content screen runs first: a suspect photo surfaces under NSFW even if it
            // would otherwise classify as a plain photo, and skips the pixel-quality pass below.
            if (embedding != null) {
                val margin = nsfwMargin(embedding)
                if (margin != null && margin >= NSFW_MARGIN) {
                    categoryItems[Category.NSFW]!!.add(item)
                    continue
                }
            }
            val forcedClass = when {
                looksLikeScreenshot(item) -> ClipClass.SCREENSHOT
                looksLikeSticker(item) -> ClipClass.STICKER
                else -> null
            }

            val cls = forcedClass ?: classifyByClip(embedding, photoVec, classVecs)
            val category = cls?.category
            if (category != null) {
                categoryItems[category]!!.add(item)
                // Stickers, emoji and memes are almost always disposable — pre-select them.
                if (category == Category.LIKELY_CLUTTER) {
                    suggested[category]!!.add(item.uri)
                }
            } else {
                photoCandidates.add(item)
            }
        }

        // 4) Single decode pass over real photos → blur / dark / bright; low-res from metadata.
        fun snapshot() = Report(
            categoryItems = categoryItems.mapValues { it.value.toList() },
            suggestedDeleteUris = suggested.mapValues { it.value.toSet() },
            sizeByUri = sizeByUri
        )

        // Show the fast (embedding + metadata) categories immediately so the screen is live.
        onPartial(snapshot())

        // Re-seed previously found quality results so a resumed scan keeps prior findings.
        val qualityCategories = listOf(Category.BLURRY, Category.DARK, Category.BRIGHT, Category.LOW_RESOLUTION)
        for (category in qualityCategories) {
            val priorUris = resumeQuality[category].orEmpty()
            if (priorUris.isEmpty()) continue
            for (item in photoCandidates) {
                if (item.uri.toString() in priorUris) {
                    categoryItems[category]!!.add(item)
                    if (category == Category.BLURRY) suggested[category]!!.add(item.uri)
                }
            }
        }

        // Scan only photos not already processed (resume-friendly); most-recent first.
        val scanList = photoCandidates
            .filter { it.uri.toString() !in scannedUris }
            .sortedByDescending { it.dateMillis }
        val totalCandidates = photoCandidates.size
        var scanned = scannedUris.size
        onProgress(scanned, totalCandidates)
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
            scannedUris.add(item.uri.toString())
            scanned++
            if (scanned % 40 == 0 || scanned == totalCandidates) {
                onProgress(scanned, totalCandidates)
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

    /**
     * Bursts = time-cluster then similarity-group. Photos are sorted by capture time and split
     * into clusters where consecutive shots are ≤[BURST_TIME_GAP_MS] apart — equal timestamps
     * chain too, since rapid bursts frequently share the same second — and a longer gap starts a
     * new moment. Within each cluster, shots whose CLIP cosine is ≥[BURST_SIMILARITY] union into
     * groups of ≥[BURST_MIN_PHOTOS]. Clusters beyond [MAX_BURST_CLUSTER] photos are timelapse-
     * style deliberate runs, not accidental bursts, and are skipped.
     */
    private fun groupBursts(
        items: List<GalleryRepository.MediaItem>,
        embeddings: Map<String, FloatArray>
    ): List<List<GalleryRepository.MediaItem>> {
        if (items.size < BURST_MIN_PHOTOS) return emptyList()

        val clusters = mutableListOf<List<GalleryRepository.MediaItem>>()
        var current = mutableListOf<GalleryRepository.MediaItem>()
        for (item in items.sortedBy { it.dateMillis }) {
            val last = current.lastOrNull()
            if (last != null && item.dateMillis - last.dateMillis in 0..BURST_TIME_GAP_MS) {
                current.add(item)
            } else {
                if (current.size >= BURST_MIN_PHOTOS) clusters.add(current)
                current = mutableListOf(item)
            }
        }
        if (current.size >= BURST_MIN_PHOTOS) clusters.add(current)

        val groups = mutableListOf<List<GalleryRepository.MediaItem>>()
        for (cluster in clusters) {
            if (cluster.size > MAX_BURST_CLUSTER) continue
            val n = cluster.size
            val parent = IntArray(n) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                var c = x
                while (parent[c] != c) { val next = parent[c]; parent[c] = r; c = next }
                return r
            }
            val vecs = cluster.map { embeddings[it.uri.toString()]!! }
            for (i in 0 until n) {
                for (j in i + 1 until n) {
                    if (EmbeddingUtils.cosineSimilarity(vecs[i], vecs[j]) >= BURST_SIMILARITY) {
                        parent[find(i)] = find(j)
                    }
                }
            }
            val buckets = LinkedHashMap<Int, MutableList<GalleryRepository.MediaItem>>()
            for (i in 0 until n) buckets.getOrPut(find(i)) { mutableListOf() }.add(cluster[i])
            for (bucket in buckets.values) if (bucket.size >= BURST_MIN_PHOTOS) groups.add(bucket)
        }
        return groups
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
