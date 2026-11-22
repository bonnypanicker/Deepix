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

    @Test
    fun narrowingTheScopeNeedsNoPass() {
        // "All folders" -> a subset only drops work. The dropped folders' indexed photos stay
        // searchable, so there is nothing to re-encode.
        assertEquals(emptySet<String>(), IndexScopeStore.addedFolders(emptySet(), setOf("Camera"), known))
        assertEquals(
            emptySet<String>(),
            IndexScopeStore.addedFolders(setOf("Camera", "Downloads"), setOf("Camera"), known)
        )
    }

    @Test
    fun wideningTheScopeNamesOnlyTheNewlyCoveredFolders() {
        assertEquals(setOf("Downloads", "Screenshots"), IndexScopeStore.addedFolders(setOf("Camera"), emptySet(), known))
        assertEquals(setOf("Downloads"), IndexScopeStore.addedFolders(setOf("Camera"), setOf("Camera", "Downloads"), known))
    }

    @Test
    fun anUnchangedSelectionNeedsNoPass() {
        assertEquals(emptySet<String>(), IndexScopeStore.addedFolders(emptySet(), emptySet(), known))
        assertEquals(emptySet<String>(), IndexScopeStore.addedFolders(setOf("Camera"), setOf("Camera"), known))
    }
}
