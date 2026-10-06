package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The [EmbeddingFreshness] decision table. Everything it consults is Android-free on purpose: which of
 * four file properties are allowed to differ is the whole mechanism, and it has to be checked without a
 * device attached.
 */
class EmbeddingFreshnessTest {

    private val current = MediaSignature(
        dateModifiedMillis = 1_700_000_000_000L,
        sizeBytes = 4_815_162L,
        width = 4032,
        height = 3024
    )

    @Test
    fun aPhotoWithoutAnEmbeddingIsEncoded() {
        assertEquals(
            EmbeddingFreshness.Decision.Encode,
            EmbeddingFreshness.decide(embedded = false, recorded = null, current = current)
        )
    }

    @Test
    fun anEmbeddingFromThisExactFileIsCurrent() {
        // A different instance holding the same numbers: comparing by value is what keeps a steady
        // library from being re-encoded every pass.
        val sameButRebuilt = MediaSignature(current.dateModifiedMillis, current.sizeBytes, current.width, current.height)
        assertEquals(
            EmbeddingFreshness.Decision.Current,
            EmbeddingFreshness.decide(embedded = true, recorded = sameButRebuilt, current = current)
        )
    }

    @Test
    fun anEmbeddingMissingItsRecordIsSignedRatherThanReencoded() {
        assertEquals(
            EmbeddingFreshness.Decision.Backfill,
            EmbeddingFreshness.decide(embedded = true, recorded = null, current = current)
        )
    }

    @Test
    fun everyChangedFilePropertySendsThePhotoBackToTheEncoder() {
        val changed = listOf(
            "modified time" to current.copy(dateModifiedMillis = current.dateModifiedMillis + 1_000L),
            "size" to current.copy(sizeBytes = current.sizeBytes - 1L),
            "width" to current.copy(width = current.width - 16),
            "height" to current.copy(height = current.height - 16)
        )
        for ((label, recorded) in changed) {
            assertEquals(
                "A $label change means the stored vector describes pixels that no longer exist",
                EmbeddingFreshness.Decision.Encode,
                EmbeddingFreshness.decide(embedded = true, recorded = recorded, current = current)
            )
        }
    }

    @Test
    fun theStaleMarkerNeverMatchesARealFile() {
        // The editor writes this instead of deleting the row. A photo whose MediaStore columns are all
        // still zeros has to count as changed too, or a cropped-to-nothing frame never re-encodes.
        val blank = MediaSignature(0L, 0L, 0, 0)
        assertEquals(
            EmbeddingFreshness.Decision.Encode,
            EmbeddingFreshness.decide(
                embedded = true,
                recorded = EmbeddingFreshness.StaleSignature,
                current = blank
            )
        )
        assertEquals(
            EmbeddingFreshness.Decision.Encode,
            EmbeddingFreshness.decide(
                embedded = true,
                recorded = EmbeddingFreshness.StaleSignature,
                current = current
            )
        )
    }

    @Test
    fun anUnembeddedPhotoIsNeverTreatedAsSigningWork() {
        assertEquals(
            EmbeddingFreshness.Decision.Encode,
            EmbeddingFreshness.decide(embedded = false, recorded = current, current = current)
        )
    }
}
