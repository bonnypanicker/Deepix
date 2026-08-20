package com.devomind.gallerysearch

import com.devomind.gallerysearch.db.PersonEntity
import java.util.Locale

/**
 * Detects references to named/labeled people inside a free-text search query
 * ("me and my brother at a beach", "photos of john").
 *
 * A query may mention several people; each mention expands to the set of person ids sharing that
 * name/label (duplicates happen), and the caller ANDs the mentions together (a photo must contain
 * every mentioned person) while OR-ing duplicates within one mention.
 *
 * Matched person words are removed from the text that CLIP/metadata then rank over — CLIP has no
 * meaningful "john" visual embedding, and metadata matching would otherwise hit filenames/tags.
 */
object PersonQueryResolver {

    data class Match(
        /** One entry per matched mention; each entry is the mention's OR-set of person ids. */
        val groups: List<Set<Long>>,
        /** Query text with person mentions and connective filler removed; may be blank. */
        val strippedText: String
    )

    /** Connective words stripped from the ranking remainder once person mentions are consumed. */
    private val Stopwords = setOf(
        "photo", "photos", "pic", "pics", "picture", "pictures", "image", "images",
        "of", "my", "our", "and", "with", "show", "find", "the", "a", "an"
    )

    /** Colloquial words → relationship keys, so "mom"/"dad"/"bro" hit the Mother/Father/Brother label. */
    private val RelationshipAliases = mapOf(
        "mom" to "mother", "mum" to "mother", "mama" to "mother", "mommy" to "mother",
        "dad" to "father", "papa" to "father", "daddy" to "father",
        "bro" to "brother",
        "sis" to "sister",
        "self" to "me", "myself" to "me",
        "gf" to "partner", "bf" to "partner",
        "wife" to "partner", "husband" to "partner",
        "girlfriend" to "partner", "boyfriend" to "partner",
        "coworker" to "colleague", "workmate" to "colleague",
        "buddy" to "friend", "pal" to "friend"
    )

    private val WordSplitter = Regex("[^\\p{L}\\p{N}'-]+")

    /** Returns null when nobody is labeled yet or no word in the query matches a known person. */
    fun resolve(textQuery: String, people: List<PersonEntity>): Match? {
        if (people.isEmpty() || textQuery.isBlank()) return null

        // word (lowercase) → person ids. Indexes name words, relationship keys AND labels, and aliases.
        val index = HashMap<String, MutableSet<Long>>()
        fun indexWord(word: String, personId: Long) {
            if (word.isNotBlank()) index.getOrPut(word) { mutableSetOf() } += personId
        }
        for (person in people) {
            person.nameLabel?.takeIf { it.isNotBlank() }
                ?.lowercase(Locale.getDefault())
                ?.split(Regex("\\s+"))
                ?.forEach { indexWord(it, person.personId) }
            person.relationship?.let { relationship ->
                indexWord(relationship, person.personId)
                PersonIdentity.relationshipLabel(relationship)
                    ?.lowercase(Locale.getDefault())
                    ?.let { indexWord(it, person.personId) }
            }
        }
        if (index.isEmpty()) return null

        val words = textQuery.lowercase(Locale.getDefault())
            .split(WordSplitter)
            .map { it.trim('\'', '-') }
            .filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val consumedByPerson = BooleanArray(words.size)
        val groups = mutableListOf<Set<Long>>()
        words.forEachIndexed { i, word ->
            val personIds = index[word] ?: RelationshipAliases[word]?.let { index[it] }
            if (!personIds.isNullOrEmpty()) {
                consumedByPerson[i] = true
                groups += personIds
            }
        }
        if (groups.isEmpty()) return null

        val remainder = words
            .filterIndexed { i, word -> !consumedByPerson[i] && word !in Stopwords }
            .joinToString(" ")
        return Match(groups = groups, strippedText = remainder)
    }
}
