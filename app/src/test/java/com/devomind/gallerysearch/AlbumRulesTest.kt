package com.devomind.gallerysearch

import com.devomind.gallerysearch.db.TagEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two rules a folder or tag album is built on, held apart from the screen that applies them:
 * which folders the chooser offers, and which photos an album of tags actually collects.
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
    fun theFolderChooserOffersEveryDepthBiggestFirst() {
        val roots = listOf(
            node("DCIM", itemCount = 900, children = listOf(node("DCIM/Camera", itemCount = 800))),
            node("Download", itemCount = 12)
        )
        val choices = AlbumRules.folderChoices(roots)
        assertEquals(listOf("DCIM", "DCIM/Camera", "Download"), choices.map { it.path })
        assertEquals(800, choices[1].itemCount)
        // A nested folder is offered beside its parent: that is the reach the Albums page's flat
        // bucket list does not have.
        assertTrue(choices.any { it.path.contains('/') })
    }

    @Test
    fun equallyFullFoldersComeUpByNameNotByTreeOrder() {
        val choices = AlbumRules.folderChoices(listOf(node("Zebra", itemCount = 5), node("Apple", itemCount = 5)))
        assertEquals(listOf("Apple", "Zebra"), choices.map { it.name })
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
