package com.devomind.gallerysearch

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.Locale

internal object StructuredSearch {
    data class Pill(
        val label: String,
        val token: String
    )

    data class FilterLookup(
        val tagNameToUris: Map<String, Set<String>> = emptyMap(),
        val exifByUri: Map<String, ExifData> = emptyMap()
    )

    data class ParsedQuery(
        val rawTokens: List<String>,
        val normalizedTokens: Set<String>,
        val textTokens: List<String>,
        val filters: List<Filter>
    ) {
        val textQuery: String = textTokens.joinToString(" ")
        val hasAnyCriteria: Boolean
            get() = textTokens.isNotEmpty() || filters.isNotEmpty()

        val needsFilterLookup: Boolean
            get() = filters.any { it is TagFilter || it is MakeFilter || it is ModelFilter || it is IsoFilter || it is FocalLengthFilter }

        fun filterItems(
            items: List<GalleryRepository.MediaItem>,
            favoriteKeys: Set<String>,
            lookup: FilterLookup = FilterLookup()
        ): List<GalleryRepository.MediaItem> {
            if (filters.isEmpty()) return items
            return items.filter { item -> filters.all { it.matches(item, favoriteKeys, lookup) } }
        }
    }

    sealed interface Filter {
        val rawToken: String
        val chipLabel: String
        fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean
    }

    internal data class FavoriteFilter(
        override val rawToken: String,
        val expected: Boolean
    ) : Filter {
        override val chipLabel: String = if (expected) "favorites" else "not favorite"
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            val isFavorite = item.uri.toString() in favoriteKeys
            return isFavorite == expected
        }
    }

    internal data class AlbumFilter(
        override val rawToken: String,
        val value: String
    ) : Filter {
        override val chipLabel: String = "album ${value.lowercase(Locale.getDefault())}"
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            return item.bucketName.contains(value, ignoreCase = true)
        }
    }

    internal data class ExtensionFilter(
        override val rawToken: String,
        val value: String
    ) : Filter {
        override val chipLabel: String = value.uppercase(Locale.ROOT)
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            val extension = item.displayName.orEmpty().substringAfterLast('.', "").lowercase(Locale.ROOT)
            return extension == value
        }
    }

    internal data class MimeFilter(
        override val rawToken: String,
        val value: String
    ) : Filter {
        override val chipLabel: String = value
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            return item.mimeType.orEmpty().lowercase(Locale.ROOT).contains(value)
        }
    }

    internal data class OrientationFilter(
        override val rawToken: String,
        val value: String
    ) : Filter {
        override val chipLabel: String = value
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            val orientation = when {
                item.width > item.height -> "landscape"
                item.height > item.width -> "portrait"
                item.width > 0 && item.width == item.height -> "square"
                else -> "photo"
            }
            return orientation == value
        }
    }

    internal data class TypeFilter(
        override val rawToken: String,
        val value: String
    ) : Filter {
        override val chipLabel: String = value
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            return when (value) {
                "image", "photo", "photos" -> item.mediaType == GalleryRepository.MediaType.Image
                "video", "videos" -> item.mediaType == GalleryRepository.MediaType.Video
                else -> false
            }
        }
    }

    internal data class TagFilter(
        override val rawToken: String,
        val value: String
    ) : Filter {
        override val chipLabel: String = "tag ${value.lowercase(Locale.getDefault())}"
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            return lookup.tagNameToUris[value.lowercase(Locale.getDefault())]?.contains(item.uri.toString()) == true
        }
    }

    internal data class MakeFilter(
        override val rawToken: String,
        val value: String
    ) : Filter {
        override val chipLabel: String = "make ${value.lowercase(Locale.getDefault())}"
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            return lookup.exifByUri[item.uri.toString()]?.make?.contains(value, ignoreCase = true) == true
        }
    }

    internal data class ModelFilter(
        override val rawToken: String,
        val value: String
    ) : Filter {
        override val chipLabel: String = "model ${value.lowercase(Locale.getDefault())}"
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            return lookup.exifByUri[item.uri.toString()]?.model?.contains(value, ignoreCase = true) == true
        }
    }

    internal data class IsoFilter(
        override val rawToken: String,
        val operator: Operator,
        val value: Int
    ) : Filter {
        override val chipLabel: String = "iso${operator.symbol}$value"
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            val actual = lookup.exifByUri[item.uri.toString()]?.iso ?: return false
            return operator.compare(actual, value)
        }
    }

    internal data class FocalLengthFilter(
        override val rawToken: String,
        val operator: Operator,
        val value: Int
    ) : Filter {
        override val chipLabel: String = "focal${operator.symbol}$value"
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            val actual = lookup.exifByUri[item.uri.toString()]?.focalLength?.toInt() ?: return false
            return operator.compare(actual, value)
        }
    }

    internal data class ComparisonFilter(
        override val rawToken: String,
        val field: String,
        val operator: Operator,
        val value: Int
    ) : Filter {
        override val chipLabel: String = "$field${operator.symbol}$value"
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            val actual = when (field) {
                "id" -> item.uri.lastPathSegment?.toIntOrNull()
                "year" -> item.dateMillis.toLocalDate().year
                "month" -> item.dateMillis.toLocalDate().monthValue
                "day" -> item.dateMillis.toLocalDate().dayOfMonth
                "width" -> item.width
                "height" -> item.height
                else -> null
            } ?: return false
            return operator.compare(actual, value)
        }
    }

    internal data class DateFilter(
        override val rawToken: String,
        val range: DateRange
    ) : Filter {
        override val chipLabel: String = range.label
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            return item.dateMillis in range.startMillis until range.endExclusiveMillis
        }
    }

    internal data class BeforeFilter(
        override val rawToken: String,
        val range: DateRange
    ) : Filter {
        override val chipLabel: String = "before ${range.label.lowercase(Locale.getDefault())}"
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            return item.dateMillis < range.startMillis
        }
    }

    internal data class AfterFilter(
        override val rawToken: String,
        val range: DateRange
    ) : Filter {
        override val chipLabel: String = "after ${range.label.lowercase(Locale.getDefault())}"
        override fun matches(item: GalleryRepository.MediaItem, favoriteKeys: Set<String>, lookup: FilterLookup): Boolean {
            return item.dateMillis >= range.startMillis
        }
    }

    internal enum class Operator(val symbol: String) {
        Equals("="),
        LessThan("<"),
        GreaterThan(">");

        fun compare(actual: Int, expected: Int): Boolean {
            return when (this) {
                Equals -> actual == expected
                LessThan -> actual < expected
                GreaterThan -> actual > expected
            }
        }

        companion object {
            fun from(raw: String): Operator {
                return when (raw) {
                    "<" -> LessThan
                    ">" -> GreaterThan
                    else -> Equals
                }
            }
        }
    }

    private val FieldPattern = Regex("""^(?<key>[A-Za-z_]+)(?<operator>[:=<>])(?<value>.+)$""")

    fun parse(query: String): ParsedQuery {
        val rawTokens = tokenize(query)
        val textTokens = ArrayList<String>()
        val filters = ArrayList<Filter>()

        rawTokens.forEach { token ->
            val filter = parseFilter(token)
            if (filter != null) {
                filters += filter
            } else {
                textTokens += unquote(token)
            }
        }

        return ParsedQuery(
            rawTokens = rawTokens,
            normalizedTokens = rawTokens.mapTo(LinkedHashSet()) { canonicalToken(it) },
            textTokens = textTokens.filter { it.isNotBlank() },
            filters = filters
        )
    }

    fun canonicalToken(token: String): String = token.trim().lowercase(Locale.ROOT)

    private fun parseFilter(token: String): Filter? {
        val match = FieldPattern.matchEntire(token.trim()) ?: return null
        val key = match.groups["key"]?.value?.trim()?.lowercase(Locale.ROOT) ?: return null
        val operator = Operator.from(match.groups["operator"]?.value ?: "=")
        val rawValue = match.groups["value"]?.value?.trim().orEmpty()
        val value = unquote(rawValue).trim()
        if (value.isBlank()) return null

        return when (key) {
            "fav", "favorite", "favorites" -> parseFavoriteFilter(token, value)
            "album", "bucket" -> AlbumFilter(token, value)
            "ext", "format" -> ExtensionFilter(token, value.removePrefix(".").lowercase(Locale.ROOT))
            "mime" -> MimeFilter(token, value.lowercase(Locale.ROOT))
            "orientation" -> OrientationFilter(token, value.lowercase(Locale.ROOT))
            "type" -> TypeFilter(token, value.lowercase(Locale.ROOT))
            "tag" -> TagFilter(token, value)
            "make" -> MakeFilter(token, value)
            "model" -> ModelFilter(token, value)
            "iso" -> parseNumericFilter(token, value, operator, ::IsoFilter)
            "focal" -> parseNumericFilter(token, value, operator, ::FocalLengthFilter)
            "year", "month", "day", "width", "height", "id" -> {
                val numericValue = value.toIntOrNull() ?: return null
                ComparisonFilter(token, key, operator, numericValue)
            }
            "date", "before", "after" -> parseDateFilter(token, key, value)
            else -> null
        }
    }

    private inline fun parseNumericFilter(
        token: String,
        value: String,
        operator: Operator,
        factory: (String, Operator, Int) -> Filter
    ): Filter? {
        val numericValue = value.toIntOrNull() ?: return null
        return factory(token, operator, numericValue)
    }

    private fun parseDateFilter(token: String, key: String, value: String): Filter? {
        val range = parseDateRange(value) ?: return null
        return when (key) {
            "date" -> DateFilter(token, range)
            "before" -> BeforeFilter(token, range)
            "after" -> AfterFilter(token, range)
            else -> null
        }
    }

    private fun parseFavoriteFilter(rawToken: String, value: String): Filter? {
        val expected = when (value.lowercase(Locale.ROOT)) {
            "yes", "true", "1", "on" -> true
            "no", "false", "0", "off" -> false
            else -> return null
        }
        return FavoriteFilter(rawToken, expected)
    }

    private fun Long.toLocalDate(): LocalDate =
        java.time.Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()).toLocalDate()

    private fun tokenize(query: String): List<String> {
        val tokens = ArrayList<String>()
        val current = StringBuilder()
        var inQuotes = false
        query.trim().forEach { char ->
            when {
                char == '"' -> {
                    inQuotes = !inQuotes
                    current.append(char)
                }
                char.isWhitespace() && !inQuotes -> {
                    if (current.isNotEmpty()) {
                        tokens += current.toString()
                        current.clear()
                    }
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) {
            tokens += current.toString()
        }
        return tokens
    }

    private fun unquote(value: String): String {
        return if (value.length >= 2 && value.startsWith('"') && value.endsWith('"')) {
            value.substring(1, value.length - 1)
        } else {
            value
        }
    }
}

internal data class DateRange(
    val startMillis: Long,
    val endExclusiveMillis: Long,
    val label: String
)

private fun parseDateRange(value: String): DateRange? {
    val zone = ZoneId.systemDefault()
    return when {
        value.matches(Regex("""\d{4}""")) -> {
            val year = value.toInt()
            val start = LocalDate.of(year, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = LocalDate.of(year + 1, 1, 1).atStartOfDay(zone).toInstant().toEpochMilli()
            DateRange(start, end, value)
        }
        value.matches(Regex("""\d{4}-\d{2}""")) -> {
            val month = YearMonth.parse(value)
            val start = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli()
            DateRange(start, end, monthLabel(month))
        }
        value.matches(Regex("""\d{4}-\d{2}-\d{2}""")) -> {
            val date = LocalDate.parse(value)
            val start = date.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            DateRange(start, end, value)
        }
        else -> null
    }
}

private fun monthLabel(month: YearMonth): String {
    val name = month.month.name.lowercase(Locale.getDefault())
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
    return "$name ${month.year}"
}
