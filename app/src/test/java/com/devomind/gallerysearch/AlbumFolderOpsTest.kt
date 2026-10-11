package com.devomind.gallerysearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule that decides whether an original may be deleted after its copy lands in an album's
 * folder. It is held apart from Android because getting it wrong loses a photo rather than a file, and
 * because it is deliberately stricter than the Recycle Bin's own check: the bin tolerates not knowing
 * how big the photo was, and a move cannot.
 */
class AlbumFolderOpsTest {

    @Test
    fun onlyAWholeFileIsAWholeCopy() {
        assertTrue(AlbumFolderOps.copyVerified(expectedBytes = 4096, writtenBytes = 4096))
        // Short by a byte is a truncated photo, and long means these are not the same file at all.
        assertFalse(AlbumFolderOps.copyVerified(expectedBytes = 4096, writtenBytes = 4095))
        assertFalse(AlbumFolderOps.copyVerified(expectedBytes = 4096, writtenBytes = 4097))
    }

    @Test
    fun anEmptyFileProvesNothing() {
        assertFalse(AlbumFolderOps.copyVerified(expectedBytes = 0, writtenBytes = 0))
        assertFalse(AlbumFolderOps.copyVerified(expectedBytes = -1, writtenBytes = 8192))
    }

    @Test
    fun aCopyNobodyCouldMeasureIsRefused() {
        // MediaStore handed back no SIZE for the original. "Non-empty" would let the bin's rule pass it,
        // and then the delete would go ahead on evidence that says nothing.
        assertFalse(AlbumFolderOps.copyVerified(expectedBytes = 0, writtenBytes = 1))
    }
}
