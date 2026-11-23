package com.devomind.gallerysearch

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A platform member that only exists on newer Android must not be reached on older ones. Two were,
 * and neither showed up as a `lintVitalRelease` failure (it only runs fatal issues), so both are
 * pinned here instead.
 *
 * **`MediaStore.AUTHORITY_URI` (API 29)** — a static `Uri` field, so the read is resolved at runtime
 * rather than inlined. On Android 8/9 it threw `NoSuchFieldError` on the first statement of
 * `initializeCore`, before the app could draw:
 *
 * ```
 * Exception java.lang.NoSuchFieldError:
 *   at MainActivity.initializeCore (MainActivity.kt:916)
 *   at MainActivity.requestGalleryPermission (MainActivity.kt:864)
 * ```
 *
 * **`PowerManager.isDeviceLightIdleMode` (API 33)** — gated on `M` by copy-paste from the
 * `isDeviceIdleMode` line above it, so every hardware probe threw `NoSuchMethodError` on Android
 * 6–12L and the indexing pass failed there.
 */
class ApiLevelGuardTest {

    private val main = source("MainActivity.kt")
    private val hardwareMonitor = source("IndexHardwareMonitor.kt")

    @Test
    fun theMediaObserverDoesNotReadTheApi29AuthorityField() {
        assertFalse(
            "MediaStore.AUTHORITY_URI does not exist before API 29 — reading it crashes launch on Android 8/9",
            Regex("""MediaStore\.AUTHORITY_URI""").containsMatchIn(codeOnly(main))
        )
    }

    @Test
    fun theAuthorityRootIsBuiltFromTheCompileTimeConstant() {
        assertTrue(
            "the MediaStore root must come from the API 1 `AUTHORITY` constant",
            """Uri.parse("content://${'$'}{MediaStore.AUTHORITY}")""" in main
        )
        assertTrue(
            "the observer must register the field-derived URI",
            "registerContentObserver(mediaStoreAuthorityUri" in main
        )
    }

    @Test
    fun theLightIdleProbeIsGatedOnApi33() {
        val guard = hardwareMonitor.substringAfter("isDeviceLightIdle =")
            .substringBefore("\n            }")
        assertTrue(
            "isDeviceLightIdleMode is API 33; the guard must name it (or 33), not M",
            "Build.VERSION_CODES.TIRAMISU" in guard || "SDK_INT >= 33" in guard
        )
        assertFalse("the M guard belongs to isDeviceIdleMode", "Build.VERSION_CODES.M" in guard)
    }

    /** Drops comments, so a KDoc explaining the crash does not count as a reference to the field. */
    private fun codeOnly(text: String): String =
        text.lines()
            .filterNot { line ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }
            .joinToString("\n")

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
