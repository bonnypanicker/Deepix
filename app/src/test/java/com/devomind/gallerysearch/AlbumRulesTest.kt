package com.devomind.gallerysearch

import com.devomind.gallerysearch.db.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules a folder or tag album is built on, held apart from the screen that applies them: where an
 * album's own folder may be made, which folder an album reads back out of the tree, and which photos a
 * set of tags collects.
 */
class AlbumRulesTest {

    private fun node(
        path: String,
        name: String = path.substringAfterLast('/'),
        itemCount: Int = 0,
        children: List<FolderNode> = emptyList()
    ) = FolderNode(
        path = path,
        name = name,
        depth = path.split('/').size - 1,
        coverUri = null,
        itemCount = itemCount,
        imageCount = itemCount,
        videoCount = 0,
        folderCount = children.size,
        sizeBytes = 0L,
        latestDateMillis = 0L,
        children = children
    )

    @Test
    fun anAlbumFolderSitsUnderTheChosenRootAndItsParent() {
        assertEquals(
            "Pictures/Deepix Albums/Weekend trips",
            AlbumRules.albumFolderPath("Pictures", AlbumRules.AlbumFolderParent, "Weekend trips")
        )
        assertEquals(
            "Documents/Deepix Albums/Weekend trips",
            AlbumRules.albumFolderPath("Documents", AlbumRules.AlbumFolderParent, "Weekend trips")
        )
        // Whatever the app names a folder, the album that made it recognises it — otherwise a brand-new
        // album reports its own folder as one that went away.
        assertTrue(
            AlbumRules.isAlbumFolderPath(
                AlbumRules.albumFolderPath("Pictures", AlbumRules.AlbumFolderParent, "Trips")!!
            )
        )
    }

    @Test
    fun aFolderNameCannotEscapeTheParentItWasMadeUnder() {
        // Separators and control characters are the only way out of a relative path, so they become
        // spaces rather than structure: the album still gets a folder, just not one it shouldn't have.
        listOf("../../etc", "a/b", "..\\..\\windows", "C:/Windows", "one\u0000two").forEach { typed ->
            val path = AlbumRules.albumFolderPath("Pictures", "Deepix Albums", typed)
            assertTrue("expected a path for \"$typed\"", path != null)
            assertEquals(
                "\"$typed\" reached past the album parent",
                2,
                path!!.count { it == '/' }
            )
        }
        // Nothing usable is left, so no folder is named and no album is made.
        assertNull(AlbumRules.albumFolderPath("Pictures", "Deepix Albums", "   "))
        assertNull(AlbumRules.albumFolderPath("Pictures", "Deepix Albums", "..."))
        assertNull(AlbumRules.albumFolderPath("Pictures", "Deepix Albums", ".."))
        assertNull(AlbumRules.albumFolderPath("Pictures", "Deepix Albums", "/"))
    }

    @Test
    fun aLongNameIsCappedNotTruncatedMidPath() {
        val path = AlbumRules.albumFolderPath("Pictures", "Deepix Albums", "x".repeat(300))!!
        assertTrue(path.length < "Pictures/Deepix Albums/".length + 61)
        assertEquals("Pictures/Deepix Albums/" + "x".repeat(60), path)
    }

    @Test
    fun anAlbumsOwnFolderIsToldApartFromOneItWasPointedAt() {
        assertTrue(AlbumRules.isAlbumFolderPath("Pictures/Deepix Albums/Weekend trips"))
        assertTrue(AlbumRules.isAlbumFolderPath("Documents/Deepix Albums/Receipts"))
        // Mirrored folders, however deep or similarly named, sit somewhere the app did not make.
        assertFalse(AlbumRules.isAlbumFolderPath("DCIM/Camera"))
        assertFalse(AlbumRules.isAlbumFolderPath("Pictures/Screenshots"))
        assertFalse(AlbumRules.isAlbumFolderPath("Pictures/Holiday/Deepix Albums"))
        assertFalse(AlbumRules.isAlbumFolderPath(""))
    }

    @Test
    fun aFolderPathIsFoundWhereverItSitsOrNotAtAll() {
        val roots = listOf(node("Pictures", itemCount = 30, children = listOf(node("Pictures/Trips/2026"))))
        assertEquals("2026", AlbumRules.findFolderNode(roots, "Pictures/Trips/2026")?.name)
        // A folder the tree never materialized holds no photos, and an album for it shows none.
        assertNull(AlbumRules.findFolderNode(roots, "Pictures/Trips"))
        assertNull(AlbumRules.findFolderNode(roots, "Pictures/Other"))
        assertNull(AlbumRules.findFolderNode(emptyList(), "Pictures"))
    }

    @Test
    fun aTagAlbumCollectsPhotosCarryingAnyOfItsTags() {
        val tags = listOf(TagEntity(1, "trip", 0, 0L), TagEntity(2, "beach", 0, 0L), TagEntity(3, "work", 0, 0L))
        val uris = mapOf(1L to setOf("a", "b"), 2L to setOf("b", "c"), 3L to setOf("z"))
        // "b" carries both picked tags and is collected once, and "z" stays out of the album.
        assertEquals(setOf("a", "b", "c"), AlbumRules.tagUris(listOf("trip", "beach"), tags, uris))
        assertEquals(setOf("z"), AlbumRules.tagUris(listOf("work"), tags, uris))
        assertEquals(emptySet<String>(), AlbumRules.tagUris(emptyList(), tags, uris))
    }

    @Test
    fun aTagThatWentAwayAddsNothingAndCannotReachAnother() {
        val tags = listOf(TagEntity(1, "trip", 0, 0L))
        val uris = mapOf(1L to setOf("a"))
        // The album still names "beach". Resolving by name means a vanished tag contributes nothing,
        // instead of following rowid 2 over to whichever tag holds that id next.
        assertEquals(setOf("a"), AlbumRules.tagUris(listOf("trip", "beach"), tags, uris))
        assertEquals(emptySet<String>(), AlbumRules.tagUris(listOf("beach"), tags, uris))
    }

    @Test
    fun everyIdCarriesItsKind() {
        // A folder album is keyed by the folder, which is also why one folder cannot yield two.
        assertEquals("folder:DCIM/Camera", CollectionAlbumStore.folderIdFor("DCIM/Camera"))
        assertTrue(CollectionAlbumStore.smartId().startsWith(CollectionAlbumStore.SMART_PREFIX))
        assertTrue(CollectionAlbumStore.tagsId().startsWith(CollectionAlbumStore.TAGS_PREFIX))
    }

    @Test
    fun aLiveAlbumReportsWhatTheLibrarySaysNotWhatWasStored() {
        val album = CollectionAlbum(
            id = CollectionAlbumStore.folderIdFor("DCIM/Camera"),
            name = "Camera",
            prompt = "",
            searchMode = "",
            memberUris = listOf("yesterday", "and the day before"),
            coverUri = null,
            createdAt = 0L,
            updatedAt = 0L,
            kind = AlbumKind.Folder,
            folderPath = "DCIM/Camera"
        )
        val cell = album.toAlbum(count = 41)
        assertEquals(41, cell.count)
        assertEquals(AlbumKind.Folder, cell.kind)
        // A smart album has no library to ask, so its stored list is the answer.
        assertEquals(2, album.copy(kind = AlbumKind.Smart).toAlbum().count)
    }
}
