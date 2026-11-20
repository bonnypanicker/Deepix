package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * One query must not be encoded twice.
 *
 * The text tokenizer prefixes every phrasing with `"a photo of "`, so the variant list that
 * [QueryVariants] builds from raw query text used to contain the same token sequence twice: "blue
 * shirt person" and "a photo of blue shirt person" both reached the model as the identical caption,
 * and one of the two full passes over the index was guaranteed to return the same scores as the
 * other. De-duplicating after the prefix removes that pass without changing which phrasings are
 * tried — the set of encoded texts stays the same.
 */
class QueryVariantsTest {

    @Test
    fun noPhrasingIsSentToTheModelTwice() {
        for (query in listOf("blue shirt person", "dog", "a photo of a dog", "screenshots  of   notes")) {
            val variants = QueryVariants.build(query)
            assertEquals(
                "\"$query\" produced the same caption more than once",
                variants.size,
                variants.toSet().size
            )
        }
    }

    @Test
    fun everyPhrasingIsAlreadyInTheCaptionShape() {
        // The tokenizer applies the prefix again, so a variant that is not already prefixed would be
        // turned into a different caption than the one this list believes it is trying.
        for (query in listOf("blue shirt person", "a photo of a dog", " sunset ")) {
            for (variant in QueryVariants.build(query)) {
                assertEquals(variant, TextTokenizer.withPhotoPrefix(variant))
            }
        }
    }

    @Test
    fun applyingTheCaptionPrefixTwiceChangesNothing() {
        val once = TextTokenizer.withPhotoPrefix("blue shirt person")
        assertEquals(once, TextTokenizer.withPhotoPrefix(once))
        assertEquals("a photo of blue shirt person", once)
    }

    @Test
    fun aQueryThatAlreadyCarriesThePrefixKeepsItsExtraShape() {
        // The duplicate that was removed is the one every plain query produces. A query that already
        // starts with the prefix really does have a distinct "a photo of a photo of …" phrasing, and
        // dropping it here would have quietly changed what that search encodes.
        val variants = QueryVariants.build("a photo of a dog")
        assertEquals(4, variants.size)
        assertTrue("a photo of a photo of a dog" in variants)
    }

    @Test
    fun aBlankQueryStillReachesTheTokenizer() {
        // The tokenizer turns this into the bare caption "a photo"; the variant list must stay a list
        // of one rather than becoming empty, or the search would return nothing before it starts.
        assertEquals(1, QueryVariants.build("   ").size)
    }
}
