package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The stored form of a folder-picker selection. "Everything checked" has to become the empty set, not
 * a list of the folders that happen to exist: that list was the bug behind a paused index restarting
 * on the way out of the screen, because writing it down read as a scope change on every exit.
 */
class IndexScopeStoreTest {

    private val known = setOf("Camera", "Downloads", "Screenshots")

    @Test
    fun everyFolderCheckedIsStoredAsAllFolders() {
        assertEquals(emptySet<String>(), IndexScopeStore.normalizeSelection(known, known))
    }

    @Test
    fun nothingCheckedFallsBackToAllFolders() {
        assertEquals(emptySet<String>(), IndexScopeStore.normalizeSelection(emptySet(), known))
    }

    @Test
    fun aRealSubsetIsStoredAsAList() {
        assertEquals(setOf("Camera"), IndexScopeStore.normalizeSelection(setOf("Camera"), known))
    }

    @Test
    fun foldersThatNoLongerExistAreDropped() {
        assertEquals(
            setOf("Camera"),
            IndexScopeStore.normalizeSelection(setOf("Camera", "OldAlbum"), known)
        )
    }

    @Test
    fun aSelectionThatCoversEveryKnownFolderIsStillAllFolders() {
        // The exact stale shape an older build left behind: every folder written out individually.
        assertEquals(emptySet<String>(), IndexScopeStore.normalizeSelection(setOf("Screenshots", "Camera", "Downloads"), known))
    }
}
