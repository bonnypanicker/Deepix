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
            "Pictures/Pixa Albums/Weekend trips",
            AlbumRules.albumFolderPath("Pictures", AlbumRules.AlbumFolderParent, "Weekend trips")
        )
        assertEquals(
            "Documents/Pixa Albums/Weekend trips",
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
    fun aPickedFolderBecomesTheRootAlbumFoldersSitUnder() {
        // A tree id is the volume and the path; MediaStore's RELATIVE_PATH is the path alone.
        assertEquals("Pictures/Trips", AlbumRules.albumRootFromTreeDocId("primary:Pictures/Trips"))
        assertEquals("Pictures/Trips", AlbumRules.albumRootFromTreeDocId("primary:Pictures/Trips/"))
        assertEquals("DCIM", AlbumRules.albumRootFromTreeDocId("primary:DCIM"))
        // The volume itself names no folder, and another volume is not the one RELATIVE_PATH addresses.
        assertNull(AlbumRules.albumRootFromTreeDocId("primary:"))
        assertNull(AlbumRules.albumRootFromTreeDocId("1A2B-3C4D:Pictures"))
        assertNull(AlbumRules.albumRootFromTreeDocId("no-colon"))
        assertNull(AlbumRules.albumRootFromTreeDocId(null))
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
        // The tile carries the folder it stands for, so the page can recognise the device bucket that
        // is that same folder and not list it a second time.
        assertEquals("DCIM/Camera", cell.folderPath)
        // A smart album has no library to ask, so its stored list is the answer.
        assertEquals(2, album.copy(kind = AlbumKind.Smart).toAlbum().count)
    }

    @Test
    fun aMediaPathBecomesTheFolderItSitsIn() {
        // RELATIVE_PATH arrives relative, with a trailing separator and without the file name.
        assertEquals(
            listOf("Pictures", "Pixa Albums", "Trips"),
            AlbumRules.folderSegments("Pictures/Pixa Albums/Trips/", "Trips", "IMG_0001.jpg")
        )
        // DATA arrives absolute and carries the file name, which is dropped only when it is the row's
        // own display name.
        assertEquals(
            listOf("DCIM", "Camera"),
            AlbumRules.folderSegments("/storage/emulated/0/DCIM/Camera/IMG_0001.jpg", "Camera", "IMG_0001.jpg")
        )
        // A card spells the volume differently, and /sdcard is the old name for primary storage.
        assertEquals(
            listOf("Pictures", "Screenshots"),
            AlbumRules.folderSegments("/storage/1234-ABCD/Pictures/Screenshots/shot.png", "Screenshots", "shot.png")
        )
        assertEquals(
            listOf("Download"),
            AlbumRules.folderSegments("/sdcard/Download/file.jpg", "Download", "file.jpg")
        )
        // A row that names no path is its bucket — which is what MediaStore calls the same folder.
        assertEquals(listOf("Camera"), AlbumRules.folderSegments("", "Camera", "IMG_0001.jpg"))
    }

    @Test
    fun theSameFolderComparesEqualWhateverItsCaseOrTrailingSlash() {
        assertEquals(
            AlbumRules.folderKey("Pictures/Pixa Albums/Trips"),
            AlbumRules.folderKey("/pictures/pixa albums/trips/")
        )
        assertEquals("pictures/pixa albums/trips", AlbumRules.folderKey("Pictures/Pixa Albums/Trips/"))
    }

    @Test
    fun aFolderAlbumHidesItsOwnDeviceBucketAndNoOther() {
        val saved = listOf(savedFolderAlbum("Pictures/Pixa Albums/Trips"))
        val buckets = listOf(
            bucket("1", "Trips", "Pictures/Pixa Albums/Trips"),
            // Same name, different folder: a real album of its own, so it stays.
            bucket("2", "Trips", "DCIM/Trips"),
            bucket("3", "Camera", "DCIM/Camera")
        )
        assertEquals(listOf("2", "3"), AlbumRules.withoutMirroredFolders(buckets, saved).map { it.id })
        // With no saved album for a folder, every bucket is still its own album.
        assertEquals(3, AlbumRules.withoutMirroredFolders(buckets, emptyList()).size)
    }

    @Test
    fun aFolderAlbumsCountComesFromTheBucketsBeneathIt() {
        val buckets = listOf(
            bucket("1", "Trips", "Pictures/Pixa Albums/Trips", count = 4),
            bucket("2", "2026", "Pictures/Pixa Albums/Trips/2026", count = 3),
            bucket("3", "Camera", "DCIM/Camera", count = 9)
        )
        assertEquals(7, AlbumRules.folderContents(buckets, "Pictures/Pixa Albums/Trips").count)
        // Paths match on whole segments, so a prefix that is not a folder finds nothing, and an album
        // with no folder at all holds nothing.
        assertEquals(0, AlbumRules.folderContents(buckets, "Pictures/Pixa Albums/Trip").count)
        assertEquals(0, AlbumRules.folderContents(buckets, "").count)
    }

    private fun bucket(
        id: String,
        name: String,
        folderPath: String,
        count: Int = 0,
        latestMillis: Long = 0L
    ) = GalleryRepository.Album(
        id = id,
        name = name,
        count = count,
        coverUri = null,
        folderPath = folderPath,
        latestMillis = latestMillis
    )

    private fun savedFolderAlbum(path: String) = CollectionAlbum(
        id = CollectionAlbumStore.folderIdFor(path),
        name = path.substringAfterLast('/'),
        prompt = "",
        searchMode = "",
        memberUris = emptyList(),
        coverUri = null,
        createdAt = 0L,
        updatedAt = 0L,
        kind = AlbumKind.Folder,
        folderPath = path
    )
}
