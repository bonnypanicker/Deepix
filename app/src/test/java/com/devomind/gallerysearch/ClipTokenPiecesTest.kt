package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The text tower's BPE merges were trained over the CLIP pre-tokenizer, which splits digits **one at
 * a time** (`[\p{N}]`). The app used `[\p{N}]+`, so a number reached the BPE as a single piece whose
 * merge path does not exist in the vocabulary — "2023" collapsed into byte-level tokens instead of
 * the digit tokens the model saw in training.
 *
 * These tests pin the split itself, which is the part that changes. Everything without a digit must
 * come out exactly as before; photo embeddings are untouched, so no re-index is involved.
 */
class ClipTokenPiecesTest {

    @Test
    fun digitsAreSplitOneByOne() {
        assertEquals(listOf("2", "0", "2", "3"), clipTokenPieces("2023"))
    }

    @Test
    fun aModelNumberIsLettersThenDigits() {
        assertEquals(listOf("iphone", "1", "5"), clipTokenPieces("iphone 15"))
    }

    @Test
    fun aResolutionIsDigitsThenItsUnit() {
        assertEquals(listOf("1", "0", "8", "0", "p"), clipTokenPieces("1080p"))
    }

    @Test
    fun textWithoutDigitsIsUnchanged() {
        assertEquals(
            listOf("a", "photo", "of", "a", "red", "car"),
            clipTokenPieces("a photo of a red car")
        )
    }

    @Test
    fun lettersAndDigitsAreLowercasedBeforeTheSplit() {
        assertEquals(listOf("iphone", "1", "5"), clipTokenPieces("IPHONE 15"))
    }

    @Test
    fun punctuationIsItsOwnPieceBetweenDigits() {
        assertEquals(listOf("5", "0", "%"), clipTokenPieces("50%"))
    }

    @Test
    fun aContractionStaysOnePiece() {
        assertEquals(listOf("don", "'t"), clipTokenPieces("don't"))
    }

    @Test
    fun theTextCountersAreSingleTokens() {
        assertEquals(listOf("<|startoftext|>"), clipTokenPieces("<|startoftext|>"))
        assertEquals(listOf("<|endoftext|>"), clipTokenPieces("<|endoftext|>"))
    }

    @Test
    fun whitespaceNeverBecomesAPiece() {
        assertEquals(listOf("new", "york"), clipTokenPieces("  new   york  "))
    }
}
