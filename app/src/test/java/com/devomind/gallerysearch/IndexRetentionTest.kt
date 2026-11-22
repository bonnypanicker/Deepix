package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Unchecking a folder must not unindex the photos already in it.
 *
 * The folder selection decides where a pass *encodes*; it used to decide what stays *searchable*,
 * because `buildIndex` reconciled the index against the ticked folders. A narrowed scope therefore
 * deleted those embeddings and the photos vanished from Smart/AI search while Albums, Metadata,
 * Tags and People went on showing them (they read MediaStore/Room). This pins the split: rows are
 * kept or dropped against the library, never against the work set.
 */
class IndexRetentionTest {

    private val camera = "content://media/external/images/media/1"
    private val downloads = "content://media/external/images/media/2"

    @Test
    fun aFolderThatWasUncheckedKeepsItsIndexedPhotos() {
        val workSet = setOf(camera)
        val library = setOf(camera, downloads)
        val indexRows = listOf(camera, downloads)

        assertTrue("the downloads photo is outside this pass's work", downloads !in workSet)
        assertTrue(
            "an already-indexed photo outside the work set must stay searchable",
            IndexRetention.keeps(downloads, library)
        )
        // The pre-fix shape, kept here as the failing case: filtering the rows by the work set
        // dropped the downloads photo and made Smart search lose it.
        assertEquals(listOf(camera), indexRows.filter { it in workSet })
        assertEquals(
            "the library keeps every row the index still holds",
            indexRows,
            indexRows.filter { IndexRetention.keeps(it, library) }
        )
    }

    @Test
    fun aPhotoThatLeftTheLibraryLosesItsRow() {
        val library = setOf(camera)

        assertFalse(IndexRetention.keeps(downloads, library))
        assertFalse("an empty library holds no rows", IndexRetention.keeps(camera, emptySet()))
    }

    @Test
    fun theReconcileKeysOffTheLibrarySet() {
        val repository = source("GalleryRepository.kt")
        val buildIndex = repository.substringAfter("suspend fun buildIndex(")
            .substringBefore("\n    private fun ")

        assertTrue(
            "buildIndex must reconcile through the retention rule",
            "IndexRetention.keeps(it, libraryUris)" in buildIndex
        )
        assertFalse(
            "the work set must never filter the index again",
            "filterKeys { it in uriSet }" in buildIndex
        )
    }

    @Test
    fun omittingTheLibrarySetFallsBackToTheWorkSet() {
        // A caller that never heard of libraryUris must still see its own photos indexed; the
        // default is the old behaviour, which is correct when the work set *is* the library.
        val repository = source("GalleryRepository.kt")
        assertTrue(
            "libraryUris must default to the items being indexed",
            "libraryUris: Set<String> = items.mapTo(HashSet()) { it.uri.toString() }" in repository
        )
    }

    @Test
    fun theWorkerPassesTheWholeLibraryOnBothPaths() {
        val worker = source("IndexWorker.kt")

        assertTrue(
            "the worker must read the whole library when the scope is narrowed",
            "repository.getImageItemsForAlbumIds(emptySet())" in worker
        )
        assertEquals(
            "both buildIndex calls must carry the library set",
            2,
            Regex("""buildIndex\([^)]*libraryUris = libraryUris""").findAll(worker).count()
        )
    }

    @Test
    fun anEmptyScopeDoesNotWipeTheIndex() {
        val worker = source("IndexWorker.kt")
        val emptyScope = worker.substringAfter("if (items.isEmpty()) {")
            .substringBefore("return Result.success()")

        assertTrue("the empty-scope branch still reconciles", "buildIndex(" in emptyScope)
        assertTrue(
            "it must carry the library set, so it prunes the library's losses only",
            "libraryUris = libraryUris" in emptyScope
        )
        assertFalse(
            "it must not prune to empty any more",
            "prune the index to empty" in worker
        )
    }

    private fun source(file: String): String =
        File(moduleDir(), "src/main/java/com/devomind/gallerysearch/$file").readText()
            .replace("\r\n", "\n")

    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir") ?: ".")
        return listOf(from, File(from, "app")).firstOrNull {
            File(it, "src/main/java/com/devomind/gallerysearch/MainActivity.kt").isFile
        } ?: throw AssertionError("could not find the app module starting from $from")
    }
}
