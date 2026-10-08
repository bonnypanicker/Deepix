package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule that decides whether a photo is offered up as a duplicate to delete, held apart from
 * Android so the exact boundary is visible: a member of a CLIP group is a copy only of the file that
 * stays, never of whatever else the group happened to link it to.
 *
 * Distances here are Hamming bits between synthetic dHashes, so each case states its own evidence —
 * 9 bits is one past [PhashUtils.NearDuplicateHammingThreshold] and 20 is the "different photo" end
 * the hash documentation describes.
 */
class DuplicateVerifierTest {

    private fun hashOf(vararg bits: Int): Long = bits.fold(0L) { acc, bit -> acc or (1L shl bit) }

    /** Verifies named shots given their file size and their dHash (null when the app has none). */
    private fun verdict(shots: Map<String, Pair<Long, Long?>>): DuplicateVerifier.Verdict<String> =
        DuplicateVerifier.verify(
            shots.keys.toList(),
            sizeOf = { shots.getValue(it).first },
            dhashOf = { shots.getValue(it).second }
        )

    @Test
    fun theLargestFileIsTheOneThatStays() {
        val verdict = verdict(
            mapOf(
                "small" to (1_000L to hashOf()),
                "big" to (9_000L to hashOf()),
                "middle" to (4_000L to hashOf())
            )
        )
        assertEquals("big", verdict.keep)
        // Every member measured identical to the kept file is offered, and the kept file never is.
        assertEquals(setOf("small", "middle"), verdict.confirmed)
    }

    @Test
    fun aChainThatOnlyClosesThroughTheMiddlePhotoDoesNotMakeTheThirdACopy() {
        // A–B sits at 2 bits and B–C at 8, so union-find puts all three in one group; A–C is 10 bits,
        // past the threshold, and C is a different photo that the grouping merely touched.
        val a = hashOf()
        val b = hashOf(0, 1)
        val c = hashOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9)
        assertEquals(2, PhashUtils.distance(a, b))
        assertEquals(8, PhashUtils.distance(b, c))
        assertEquals(10, PhashUtils.distance(a, c))

        val verdict = verdict(
            mapOf(
                "a" to (9_000L to a),
                "b" to (8_000L to b),
                "c" to (7_000L to c)
            )
        )
        assertEquals(setOf("b"), verdict.confirmed)
    }

    @Test
    fun aCopyFarEnoughFromTheKeptFileIsNotACopy() {
        val verdict = verdict(
            mapOf(
                "a" to (9_000L to hashOf()),
                "b" to (1_000L to hashOf(0, 1, 2, 3, 4, 5, 6, 7))
            )
        )
        assertEquals("a", verdict.keep)
        assertEquals("eight bits is still the same photo", setOf("b"), verdict.confirmed)

        val twentyBitsApart = verdict(
            mapOf(
                "a" to (9_000L to hashOf()),
                "c" to (1_000L to hashOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19))
            )
        )
        assertEquals("two scenes that share a subject are not duplicates", emptySet<String>(), twentyBitsApart.confirmed)
    }

    @Test
    fun aPhotoWithoutAHashIsNeverOffered() {
        val verdict = verdict(
            mapOf(
                "a" to (9_000L to hashOf()),
                "b" to (8_000L to null)
            )
        )
        assertEquals("a", verdict.keep)
        assertEquals("no measurement, no deletion offer", emptySet<String>(), verdict.confirmed)
    }

    @Test
    fun aKeptFileWithoutAHashConfirmsNothingInItsGroup() {
        // b and c match each other exactly, but the file that stays is the one nothing was compared
        // against: without its hash the app cannot say either copy is safe to lose.
        val verdict = verdict(
            mapOf(
                "a" to (9_000L to null),
                "b" to (4_000L to hashOf(1, 2, 3)),
                "c" to (3_000L to hashOf(1, 2, 3))
            )
        )
        assertEquals("a", verdict.keep)
        assertEquals(emptySet<String>(), verdict.confirmed)
    }

    @Test
    fun aGroupOfOneKeepsItselfAndOffersNothing() {
        val verdict = verdict(mapOf("only" to (5_000L to hashOf(0, 1, 2))))
        assertEquals("only", verdict.keep)
        assertEquals(emptySet<String>(), verdict.confirmed)
    }
}
