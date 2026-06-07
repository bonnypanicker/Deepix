package com.devomind.gallerysearch

import java.util.Calendar
import java.util.Locale

internal data class SearchSources(
    val ai: Boolean = false,
    val metadata: Boolean = false
) {
    val hasAny: Boolean
        get() = ai || metadata
}

internal data class PhotoSearchResult(
    val item: GalleryRepository.MediaItem,
    val sources: SearchSources,
    val score: Float
)

internal object MetadataSearch {
    fun search(query: String, items: List<GalleryRepository.MediaItem>): List<Pair<GalleryRepository.MediaItem, Float>> {
        val parsed = ParsedMetadataQuery.from(query) ?: return emptyList()
        val locale = Locale.getDefault()
        return items.asSequence()
            .mapNotNull { item ->
                val descriptor = MetadataDescriptor.from(item, locale)
                val score = parsed.score(descriptor) ?: return@mapNotNull null
                item to score
            }
            .sortedWith(
                compareByDescending<Pair<GalleryRepository.MediaItem, Float>> { it.second }
                    .thenByDescending { it.first.dateMillis }
            )
            .toList()
    }

    private data class ParsedMetadataQuery(
        val regex: Regex?,
        val textTerms: List<String>,
        val fieldFilters: List<FieldFilter>
    ) {
        fun score(descriptor: MetadataDescriptor): Float? {
            if (regex != null && !regex.containsMatchIn(descriptor.searchableText)) return null
            if (fieldFilters.any { !it.matches(descriptor) }) return null

            var total = if (regex != null) 6f else 0f
            for (term in textTerms) {
                val termScore = descriptor.scoreTerm(term)
                if (termScore <= 0f) return null
                total += termScore
            }

            if (textTerms.isEmpty() && regex == null && fieldFilters.isNotEmpty()) {
                total = 1f
            }
            return total + (fieldFilters.size * 0.75f)
        }

        companion object {
            private val FieldPattern = Regex("""^(ext|id|year|month|day|width|height)(=|<|>)(.+)$""", RegexOption.IGNORE_CASE)

            fun from(query: String): ParsedMetadataQuery? {
                val cleaned = query.trim().replace(Regex("""\s+"""), " ")
                if (cleaned.isBlank()) return null

                if (cleaned.length > 2 && cleaned.startsWith("/") && cleaned.endsWith("/")) {
                    val regex = runCatching {
                        Regex(cleaned.substring(1, cleaned.length - 1), RegexOption.IGNORE_CASE)
                    }.getOrNull() ?: return null
                    return ParsedMetadataQuery(regex = regex, textTerms = emptyList(), fieldFilters = emptyList())
                }

                val textTerms = ArrayList<String>()
                val fieldFilters = ArrayList<FieldFilter>()
                for (token in cleaned.split(' ')) {
                    val match = FieldPattern.matchEntire(token)
                    if (match != null) {
                        val field = match.groupValues[1].lowercase(Locale.ROOT)
                        val operator = ComparisonOperator.from(match.groupValues[2])
                        val rawValue = match.groupValues[3]
                        val filter = FieldFilter.create(field, operator, rawValue) ?: return null
                        fieldFilters += filter
                    } else {
                        textTerms += token.lowercase(Locale.ROOT)
                    }
                }
                if (textTerms.isEmpty() && fieldFilters.isEmpty()) return null
                return ParsedMetadataQuery(regex = null, textTerms = textTerms, fieldFilters = fieldFilters)
            }
        }
    }

    private data class MetadataDescriptor(
        val item: GalleryRepository.MediaItem,
        val displayName: String,
        val displayNameWithoutExt: String,
        val bucketName: String,
        val mimeType: String,
        val mimeSubtype: String,
        val extension: String,
        val orientation: String,
        val year: Int,
        val month: Int,
        val day: Int,
        val monthName: String,
        val dayName: String,
        val id: String,
        val searchableText: String
    ) {
        fun scoreTerm(term: String): Float {
            val dimensionText = "${item.width}x${item.height}"
            return maxOf(
                scoreToken(displayName, term, exact = 5f, prefix = 4f, contains = 3f),
                scoreToken(displayNameWithoutExt, term, exact = 5f, prefix = 4f, contains = 3f),
                scoreToken(bucketName, term, exact = 3.25f, prefix = 2.75f, contains = 2.25f),
                scoreToken(mimeType, term, exact = 2.75f, prefix = 2.5f, contains = 2f),
                scoreToken(mimeSubtype, term, exact = 3f, prefix = 2.5f, contains = 2f),
                scoreToken(extension, term, exact = 3f, prefix = 2f, contains = 1.5f),
                scoreToken(orientation, term, exact = 1.75f, prefix = 1.25f, contains = 1f),
                scoreToken(monthName, term, exact = 2f, prefix = 1.5f, contains = 1.25f),
                scoreToken(dayName, term, exact = 2f, prefix = 1.5f, contains = 1.25f),
                scoreToken(id, term, exact = 2.5f, prefix = 1.5f, contains = 1f),
                scoreToken(dimensionText, term, exact = 2.5f, prefix = 2f, contains = 1.5f),
                if (year.toString() == term) 2f else 0f,
                if (month.toString() == term) 1.75f else 0f,
                if (day.toString() == term) 1.75f else 0f,
                if (searchableText.contains(term)) 0.75f else 0f
            )
        }

        companion object {
            fun from(item: GalleryRepository.MediaItem, locale: Locale): MetadataDescriptor {
                val displayName = item.displayName.orEmpty().lowercase(locale)
                val displayNameWithoutExt = displayName.substringBeforeLast('.', displayName)
                val extension = displayName.substringAfterLast('.', "").takeIf { it != displayName }.orEmpty()
                val bucketName = item.bucketName.lowercase(locale)
                val mimeType = item.mimeType.orEmpty().lowercase(locale)
                val mimeSubtype = mimeType.substringAfter('/', "")
                val orientation = when {
                    item.width > item.height -> "landscape"
                    item.height > item.width -> "portrait"
                    item.width > 0 && item.width == item.height -> "square"
                    else -> "photo"
                }
                val calendar = Calendar.getInstance(locale).apply {
                    timeInMillis = item.dateMillis
                }
                val year = calendar.get(Calendar.YEAR)
                val month = calendar.get(Calendar.MONTH) + 1
                val day = calendar.get(Calendar.DAY_OF_MONTH)
                val monthName = calendar.getDisplayName(Calendar.MONTH, Calendar.LONG, locale).orEmpty().lowercase(locale)
                val dayName = calendar.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.LONG, locale).orEmpty().lowercase(locale)
                val id = item.uri.lastPathSegment.orEmpty()
                val searchableText = listOf(
                    displayName,
                    displayNameWithoutExt,
                    bucketName,
                    mimeType,
                    mimeSubtype,
                    extension,
                    orientation,
                    year.toString(),
                    month.toString(),
                    day.toString(),
                    monthName,
                    dayName,
                    item.width.toString(),
                    item.height.toString(),
                    "${item.width}x${item.height}",
                    id,
                    "photo",
                    "image"
                ).filter { it.isNotBlank() }.joinToString(" ")
                return MetadataDescriptor(
                    item = item,
                    displayName = displayName,
                    displayNameWithoutExt = displayNameWithoutExt,
                    bucketName = bucketName,
                    mimeType = mimeType,
                    mimeSubtype = mimeSubtype,
                    extension = extension,
                    orientation = orientation,
                    year = year,
                    month = month,
                    day = day,
                    monthName = monthName,
                    dayName = dayName,
                    id = id,
                    searchableText = searchableText
                )
            }
        }
    }

    private data class FieldFilter(
        val field: String,
        val operator: ComparisonOperator,
        val value: String
    ) {
        fun matches(descriptor: MetadataDescriptor): Boolean {
            return when (field) {
                "ext" -> operator == ComparisonOperator.Equals && descriptor.extension == value
                "id" -> compareInt(descriptor.id.toIntOrNull())
                "year" -> compareInt(descriptor.year)
                "month" -> compareInt(descriptor.month)
                "day" -> compareInt(descriptor.day)
                "width" -> compareInt(descriptor.item.width)
                "height" -> compareInt(descriptor.item.height)
                else -> false
            }
        }

        private fun compareInt(actual: Int?): Boolean {
            val expected = value.toIntOrNull() ?: return false
            val number = actual ?: return false
            return when (operator) {
                ComparisonOperator.Equals -> number == expected
                ComparisonOperator.GreaterThan -> number > expected
                ComparisonOperator.LessThan -> number < expected
            }
        }

        companion object {
            fun create(field: String, operator: ComparisonOperator, rawValue: String): FieldFilter? {
                val normalized = rawValue.trim().lowercase(Locale.ROOT)
                if (normalized.isBlank()) return null
                return FieldFilter(field = field, operator = operator, value = normalized)
            }
        }
    }

    private enum class ComparisonOperator {
        Equals,
        GreaterThan,
        LessThan;

        companion object {
            fun from(raw: String): ComparisonOperator {
                return when (raw) {
                    ">" -> GreaterThan
                    "<" -> LessThan
                    else -> Equals
                }
            }
        }
    }

    private fun scoreToken(value: String, term: String, exact: Float, prefix: Float, contains: Float): Float {
        if (value.isBlank()) return 0f
        return when {
            value == term -> exact
            value.startsWith(term) -> prefix
            value.contains(term) -> contains
            else -> 0f
        }
    }
}
