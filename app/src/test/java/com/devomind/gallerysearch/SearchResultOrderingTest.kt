package com.devomind.gallerysearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Smart result that matches best has to be the one shown first.
 *
 * Merged search results used to be ranked by position, not by score: the semantic score was
 * `1 - index/total`, then scaled by 1.1, given a `+0.35` bonus when the same photo also matched a
 * file name or album, and nudged again by a recency boost worth up to 0.05. Position throws away the
 * margin between the best match and the tenth, so a 0.19 hit and a 0.30 hit looked equally good; the
 * bonus was worth roughly 32 places at a hundred results; and the clock could reorder results whose
 * scores were a thousandth apart.
 *
 * The score is now the cosine itself, sources stay grouped (a cosine and a rank are not comparable
 * numbers), and the date only breaks an exact tie. Nothing is added to or removed from the result
 * set - only its order changes - so no photo can disappear from a search because of this.
 */
class SearchResultOrderingTest {

    private val merge = source("MainActivity.kt")
        .substringAfter("private fun buildMergedPhotoSearchResults(")
        .substringBefore("\n    }")

    @Test
    fun theSemanticScoreIsTheSimilarityItself() {
        assertTrue(
            "the cosine has to be carried through, or the order is rank-order again",
            "entry.aiScore = hit.score" in merge
        )
        assertFalse(
            "ranking by position is what discarded the margin between hits",
            "normalizedRank(index, scopedSemanticHits.size)" in merge
        )
    }

    @Test
    fun theClockAndTheNameBonusCannotOutrankABetterMatch() {
        assertFalse("a newer photo must not jump a closer one", "recencyBoost" in merge)
        assertFalse(
            "a name match must not lift a weak visual match above a strong one",
            "0.35f" in merge
        )
        // The two surviving keys, in this order, with the date last.
        val source = merge.indexOf("compareByDescending<PhotoSearchResult> { it.sources.ai }")
        val score = merge.indexOf(".thenByDescending { it.score }")
        val date = merge.indexOf(".thenByDescending { it.item.dateMillis }")
        assertTrue("the sort keys must all be present", source >= 0 && score >= 0 && date >= 0)
        assertTrue("semantic results come first, then their similarity", source < score)
        assertTrue("the date is only a tiebreak, so it sorts last", score < date)
    }

    @Test
    fun theMetadataSectionKeepsTheRankingItAlreadyHad() {
        // Name and album hits are shown in their own section, where "best name match first" is the
        // whole ordering. Only the Smart side changed units, so only the Smart side may move.
        assertTrue(
            "metadata hits still rank by their position in the metadata match list",
            "entry.metadataScore = normalizedRank(index, metadataHits.size)" in merge
        )
    }

    private fun source(file: String): String =
        File(moduleDir(), "src/main/java/com/devomind/gallerysearch/$file").readText()
            .replace("\r\n", "\n")

    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull {
            File(it, "src/main/java/com/devomind/gallerysearch/MainActivity.kt").isFile
        } ?: throw AssertionError("could not find the app module starting from $from")
    }
}
