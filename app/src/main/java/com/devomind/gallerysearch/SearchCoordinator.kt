package com.devomind.gallerysearch

import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

object SearchCoordinator {

    /**
     * Merges AI and metadata search results for photos only.
     * Filters to baseItems scope (current album/favorites/section).
     * Returns ranked SearchMatch list.
     */
    fun mergeSearchResults(
        query: String,
        baseItems: List<GalleryRepository.MediaItem>,
        aiMatches: List<SearchMatch>
    ): List<SearchMatch> {
        // Filter base items to images only
        val photoItems = baseItems.filter { it.mediaType == GalleryRepository.MediaType.Image }
        val byUri = photoItems.associateBy { it.uri }

        // Filter AI matches to current scope
        val scopedAiMatches = aiMatches.filter { it.uri in byUri }

        // Build metadata matches
        val normalizedQuery = query.trim().lowercase(Locale.getDefault())
        val metadataMatches = photoItems.mapNotNull { item ->
            val score = metadataScore(item, normalizedQuery, Locale.getDefault())
            if (score > 0) {
                SearchMatch(
                    uri = item.uri,
                    aiScore = null,
                    metadataScore = score,
                    sources = setOf(SearchMatchSource.Metadata),
                    combinedScore = 0f
                )
            } else null
        }

        // Merge by URI
        val mergedMap = LinkedHashMap<Uri, SearchMatch>()

        // Add AI matches first
        scopedAiMatches.forEach { aiMatch ->
            mergedMap[aiMatch.uri] = aiMatch
        }

        // Merge metadata matches
        metadataMatches.forEach { metaMatch ->
            val existing = mergedMap[metaMatch.uri]
            if (existing != null) {
                // Merge: union sources, keep scores
                mergedMap[metaMatch.uri] = existing.copy(
                    metadataScore = metaMatch.metadataScore,
                    sources = existing.sources + metaMatch.sources
                )
            } else {
                mergedMap[metaMatch.uri] = metaMatch
            }
        }

        // Compute combined scores
        val matches = mergedMap.values.toList()
        if (matches.isEmpty()) return emptyList()

        val bestAi = matches.mapNotNull { it.aiScore }.maxOrNull() ?: 0f
        val bestMetadata = matches.maxOf { it.metadataScore }

        val scoredMatches = matches.map { match ->
            val aiPart = if (match.aiScore != null && bestAi > 0f) {
                (match.aiScore / bestAi).coerceIn(0f, 1f) * 0.62f
            } else 0f

            val metaPart = if (bestMetadata > 0) {
                (match.metadataScore.toFloat() / bestMetadata).coerceIn(0f, 1f) * 0.38f
            } else 0f

            val bothBonus = if (match.hasAi && match.hasMetadata) 0.18f else 0f
            val exactBonus = if (match.metadataScore >= 60) 0.08f else 0f

            val combinedScore = aiPart + metaPart + bothBonus + exactBonus

            match.copy(combinedScore = combinedScore)
        }

        // Sort by combined score, then by sources, then by individual scores
        return scoredMatches.sortedWith(
            compareByDescending<SearchMatch> { it.combinedScore }
                .thenByDescending { if (it.hasAi && it.hasMetadata) 1 else 0 }
                .thenByDescending { it.aiScore ?: 0f }
                .thenByDescending { it.metadataScore }
                .thenByDescending { byUri[it.uri]?.dateMillis ?: 0L }
        )
    }

    private fun metadataScore(
        item: GalleryRepository.MediaItem,
        normalizedQuery: String,
        locale: Locale
    ): Int {
        if (normalizedQuery.isBlank()) return 0

        var score = 0
        val displayName = item.displayName?.lowercase(locale) ?: ""
        val bucketName = item.bucketName.lowercase(locale)
        val mimeType = item.mimeType?.lowercase(locale) ?: ""

        // Tokenize query
        val tokens = normalizedQuery.split(Regex("\\s+")).filter { it.length >= 2 }

        // Display name exact contains
        if (normalizedQuery in displayName) {
            score += 60
        }

        // Display name token startsWith
        for (token in tokens) {
            if (displayName.startsWith(token)) {
                score += 20
                break
            }
        }

        // Bucket name (folder/album) contains
        if (normalizedQuery in bucketName) {
            score += 25
        }

        // MIME type contains
        if (normalizedQuery in mimeType) {
            score += 10
        }

        // Media type terms
        val mediaTerms = listOf("photo", "image", "picture")
        for (term in mediaTerms) {
            if (term in normalizedQuery) {
                score += 12
                break
            }
        }

        // Date matching
        try {
            val calendar = Calendar.getInstance().apply {
                timeInMillis = item.dateMillis
            }

            // Month + Year (e.g., "january 2024")
            val monthYearFormat = SimpleDateFormat("MMMM yyyy", locale)
            val monthYear = monthYearFormat.format(calendar.time).lowercase(locale)
            if (normalizedQuery in monthYear || monthYear in normalizedQuery) {
                score += 20
            }

            // Day format (e.g., "wed, 3")
            val dayFormat = SimpleDateFormat("EEE, d", locale)
            val day = dayFormat.format(calendar.time).lowercase(locale)
            if (normalizedQuery in day || day in normalizedQuery) {
                score += 20
            }

            // Year only
            val year = calendar.get(Calendar.YEAR).toString()
            if (year in normalizedQuery) {
                score += 15
            }
        } catch (e: Exception) {
            // Date formatting failed, skip date scoring
        }

        return score
    }
}
