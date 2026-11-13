package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EditorSavePolicyTest {

    @Test
    fun copyUsesTheOriginalsFolderOnScopedStorage() {
        assertEquals("DCIM/Camera/", EditorSavePolicy.copyDestinationFolder("DCIM/Camera/"))
        assertEquals(
            "Android/media/com.example/",
            EditorSavePolicy.copyDestinationFolder("Android/media/com.example/")
        )
    }

    @Test
    fun legacyCopyKeepsTheOriginalsParentFolder() {
        assertEquals("DCIM/Camera", EditorSavePolicy.copyDestinationFolder("DCIM/Camera"))
    }

    @Test
    fun android11MediaStoreOriginalsRequestWriteConsentBeforeSaving() {
        assertTrue(EditorSavePolicy.requiresWriteConsent(30, "content", "media"))
        assertFalse(EditorSavePolicy.requiresWriteConsent(29, "content", "media"))
        assertFalse(EditorSavePolicy.requiresWriteConsent(34, "content", "documents"))
    }

    @Test
    fun refusedScopedStorageSiblingWriteRetriesInTheOwnedFolder() {
        assertTrue(
            EditorSavePolicy.shouldRetryCopyInOwnedFolder(
                34, "DCIM/Camera/", SecurityException("write denied")
            )
        )
        assertTrue(
            EditorSavePolicy.shouldRetryCopyInOwnedFolder(
                34, "Android/media/com.example/", IllegalArgumentException("invalid path")
            )
        )
        assertFalse(
            EditorSavePolicy.shouldRetryCopyInOwnedFolder(
                28, "DCIM/Camera/", SecurityException("write denied")
            )
        )
    }

    @Test
    fun editorRoutesBothSaveActionsThroughThePermissionSafePolicy() {
        val source = File(moduleDir(), "src/main/java/com/devomind/gallerysearch/PhotoEditorActivity.kt").readText()
        assertTrue(
            "Save must request MediaStore write consent before it starts an overwrite",
            "EditorSavePolicy.requiresWriteConsent" in source
        )
        assertTrue(
            "Save a copy must retain the source folder before any permission fallback",
            "EditorSavePolicy.copyDestinationFolder" in source
        )
        val saver = File(moduleDir(), "src/main/java/com/devomind/gallerysearch/MediaImageSaver.kt").readText()
        assertTrue(
            "A rejected sibling write must retry in the app-owned MediaStore collection",
            "EditorSavePolicy.shouldRetryCopyInOwnedFolder" in saver
        )
    }

    private fun moduleDir(): File {
        val from = File(System.getProperty("user.dir"))
        return listOf(from, File(from, "app")).firstOrNull {
            File(it, "src/main/java/com/devomind/gallerysearch/PhotoEditorActivity.kt").isFile
        } ?: throw AssertionError("could not find the app module starting from $from")
    }
}
