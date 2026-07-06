package com.devomind.gallerysearch

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import java.io.File
import javax.crypto.SecretKey

/**
 * Session + storage orchestration for the encrypted photo locker.
 *
 * All safe photos live inside ONE standard AES-256 zip — the encrypted "folder" — kept in a
 * user-picked SAF folder (which survives uninstall). Because zip4j needs a real file, we keep a
 * local session mirror of the archive in cacheDir: it's synced down on unlock and pushed back up
 * after every mutation. The mirror is itself encrypted (it *is* the AES zip), and extracted
 * plaintext previews are written to temp dirs that are wiped after use.
 *
 * The unlocked password is held in memory for the life of an unlocked [SafeActivity].
 */
object SafeManager {

    /** A photo inside the vault archive. [entryName] is its name within the zip. */
    data class VaultItem(val entryName: String)

    data class ImportResult(val imported: Int, val failed: Int, val importedSources: List<Uri>)

    private const val MasterName = "DeepixSafe.zip"

    @Volatile private var sessionPassword: String? = null
    @Volatile private var thumbKey: SecretKey? = null

    val isUnlocked: Boolean get() = sessionPassword != null
    fun currentPassword(): String? = sessionPassword

    fun lock(context: Context? = null) {
        sessionPassword = null
        thumbKey = null
        context?.let { ctx ->
            runCatching {
                File(ctx.cacheDir, "safe_session").deleteRecursively()
                File(ctx.cacheDir, "safe_work").deleteRecursively()
                ctx.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("safe_view_") }
                    ?.forEach { it.deleteRecursively() }
            }
        }
    }

    // ---- Configuration ----

    fun isConfigured(context: Context): Boolean =
        SafeStore.isConfigured(context) && hasVaultAccess(context)

    fun hasVaultAccess(context: Context): Boolean {
        val tree = SafeStore.getTreeUri(context) ?: return false
        val persisted = context.contentResolver.persistedUriPermissions.any {
            it.uri == tree && it.isReadPermission && it.isWritePermission
        }
        return persisted && vaultDir(context)?.canWrite() == true
    }

    /** Takes durable read/write on the picked folder and records it as the vault. */
    fun linkVault(context: Context, treeUri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(treeUri, flags) }
        SafeStore.setTreeUri(context, treeUri)
    }

    /**
     * Opens a session for a candidate password. Verifies against the stored verifier when present,
     * otherwise (e.g. recovery after reinstall) against the archive itself, then (re)saves the
     * verifier. Returns false on a wrong password.
     */
    fun unlock(context: Context, password: String): Boolean {
        syncDown(context)
        val ok = if (SafeStore.isConfigured(context)) {
            SafeStore.verifyPassword(context, password)
        } else {
            SafeCrypto.verifyPassword(sessionZip(context), password.toCharArray())
        }
        if (!ok) return false
        if (!SafeStore.isConfigured(context)) SafeStore.savePasswordVerifier(context, password)
        openSession(context, password)
        return true
    }

    enum class SetupOutcome { CREATED, ADOPTED, WRONG_PASSWORD }

    /**
     * Links the chosen folder and either creates a fresh vault or adopts an existing one.
     * If the folder already holds an archive (recovery after reinstall / a shared vault), the
     * password is verified against it first — a mismatch returns [SetupOutcome.WRONG_PASSWORD]
     * and nothing is persisted.
     */
    fun setUpVault(context: Context, password: String, treeUri: Uri): SetupOutcome {
        linkVault(context, treeUri)
        syncDown(context)
        val zip = sessionZip(context)
        val hasExisting = zip.exists() && SafeCrypto.listEntryNames(zip).isNotEmpty()
        if (hasExisting && !SafeCrypto.verifyPassword(zip, password.toCharArray())) {
            return SetupOutcome.WRONG_PASSWORD
        }
        SafeStore.savePasswordVerifier(context, password)
        openSession(context, password)
        return if (hasExisting) SetupOutcome.ADOPTED else SetupOutcome.CREATED
    }

    private fun openSession(context: Context, password: String) {
        sessionPassword = password
        thumbKey = SafeStore.saltOrNull(context)?.let { SafeCrypto.thumbKey(password, it) }
    }

    private fun vaultDir(context: Context): DocumentFile? {
        val tree = SafeStore.getTreeUri(context) ?: return null
        return DocumentFile.fromTreeUri(context, tree)
    }

    private fun masterDoc(context: Context): DocumentFile? =
        vaultDir(context)?.findFile(MasterName)

    // ---- Session mirror ----

    private fun sessionZip(context: Context): File =
        File(context.cacheDir, "safe_session").apply { mkdirs() }.let { File(it, MasterName) }

    /** Copies the SAF master archive into the local mirror (no-op if there's nothing yet). */
    private fun syncDown(context: Context) {
        val local = sessionZip(context)
        val doc = masterDoc(context)
        if (doc == null || !doc.exists()) {
            local.delete()
            return
        }
        context.contentResolver.openInputStream(doc.uri)?.use { input ->
            local.outputStream().use { input.copyTo(it) }
        }
    }

    /** Pushes the local mirror back to the SAF master archive (creating it if needed). */
    private fun syncUp(context: Context) {
        val local = sessionZip(context)
        if (!local.exists()) return
        val dir = vaultDir(context) ?: return
        val doc = dir.findFile(MasterName) ?: dir.createFile("application/zip", MasterName)
        ?: return
        context.contentResolver.openOutputStream(doc.uri, "wt")?.use { out ->
            local.inputStream().use { it.copyTo(out) }
        }
    }

    // ---- Listing ----

    fun listItems(context: Context): List<VaultItem> =
        SafeCrypto.listEntryNames(sessionZip(context))
            .sortedDescending()
            .map { VaultItem(it) }

    // ---- Import ----

    fun importPhotos(context: Context, sources: List<Uri>): ImportResult {
        val password = sessionPassword ?: return ImportResult(0, sources.size, emptyList())
        val zip = sessionZip(context)
        val existing = SafeCrypto.listEntryNames(zip).toMutableSet()
        val work = File(context.cacheDir, "safe_work").apply { mkdirs() }
        var imported = 0
        var failed = 0
        val importedSources = mutableListOf<Uri>()
        for (source in sources) {
            val plain = File(work, "in_${System.nanoTime()}")
            try {
                val display = queryDisplayName(context, source) ?: "photo_${System.nanoTime()}.jpg"
                val entryName = uniqueEntry(display, existing)
                context.contentResolver.openInputStream(source)?.use { input ->
                    plain.outputStream().use { input.copyTo(it) }
                } ?: throw IllegalStateException("Cannot read $source")

                SafeCrypto.addFileToZip(zip, plain, entryName, password.toCharArray())
                existing.add(entryName)

                SafeCrypto.makeThumbnailJpeg(context, source)?.let { writeThumb(context, entryName, it) }
                imported++
                importedSources.add(source)
            } catch (e: Exception) {
                failed++
            } finally {
                plain.delete()
            }
        }
        if (imported > 0) syncUp(context)
        return ImportResult(imported, failed, importedSources)
    }

    // ---- Thumbnails (app-private encrypted cache, regenerable from the archive) ----

    fun thumbnail(context: Context, item: VaultItem): Bitmap? {
        val key = thumbKey ?: return null
        val cache = thumbFile(context, item.entryName)
        if (cache.exists()) {
            runCatching {
                val bytes = SafeCrypto.decryptBytes(key, cache.readBytes())
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
            cache.delete() // corrupt/stale — rebuild below
        }
        val temp = runCatching { decryptToTemp(context, item) }.getOrNull() ?: return null
        return try {
            val jpeg = SafeCrypto.makeThumbnailJpeg(context, Uri.fromFile(temp)) ?: return null
            writeThumb(context, item.entryName, jpeg)
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
        } finally {
            temp.parentFile?.deleteRecursively()
        }
    }

    // ---- View / restore / remove ----

    /** Decrypts one item and decodes a downscaled bitmap for full-screen preview (temp wiped). */
    fun decryptToBitmap(context: Context, item: VaultItem, maxPx: Int = 2560): Bitmap? {
        val temp = runCatching { decryptToTemp(context, item) }.getOrNull() ?: return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, bounds)
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            var sample = 1
            while (longest / sample > maxPx * 2) sample *= 2
            BitmapFactory.decodeFile(temp.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
        } finally {
            temp.parentFile?.deleteRecursively()
        }
    }

    /** Decrypts one item to a temp file for viewing. Caller deletes its parent dir when done. */
    fun decryptToTemp(context: Context, item: VaultItem): File {
        val password = sessionPassword ?: error("Safe is locked")
        val work = File(context.cacheDir, "safe_view_${System.nanoTime()}").apply { mkdirs() }
        return SafeCrypto.extractEntry(sessionZip(context), item.entryName, password.toCharArray(), work)
    }

    /** Decrypts back into the public gallery (Pictures/Deepix). Returns the new media uri or null. */
    fun restoreToGallery(context: Context, item: VaultItem): Uri? {
        val temp = runCatching { decryptToTemp(context, item) }.getOrNull() ?: return null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, temp.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeFor(temp.name))
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Deepix")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
            }
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val out = context.contentResolver.insert(collection, values) ?: return null
            context.contentResolver.openOutputStream(out)?.use { os ->
                temp.inputStream().use { it.copyTo(os) }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(out, values, null, null)
            }
            out
        } finally {
            temp.parentFile?.deleteRecursively()
        }
    }

    fun removeItem(context: Context, item: VaultItem): Boolean {
        val password = sessionPassword ?: return false
        return runCatching {
            SafeCrypto.removeEntry(sessionZip(context), item.entryName, password.toCharArray())
            thumbFile(context, item.entryName).delete()
            syncUp(context)
            true
        }.getOrDefault(false)
    }

    // ---- Helpers ----

    private fun thumbDir(context: Context): File =
        File(context.filesDir, "safe_thumbs").apply { mkdirs() }

    private fun thumbFile(context: Context, entryName: String): File =
        File(thumbDir(context), safeHash(entryName) + ".t")

    private fun writeThumb(context: Context, entryName: String, jpeg: ByteArray) {
        val key = thumbKey ?: return
        runCatching { thumbFile(context, entryName).writeBytes(SafeCrypto.encryptBytes(key, jpeg)) }
    }

    private fun safeHash(s: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun baseName(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) displayName.substring(0, dot) else displayName
    }

    private fun extensionOf(displayName: String): String {
        val dot = displayName.lastIndexOf('.')
        return if (dot > 0) displayName.substring(dot) else ".jpg"
    }

    /** A collision-free entry name within the archive. */
    private fun uniqueEntry(displayName: String, taken: Set<String>): String {
        if (displayName !in taken) return displayName
        val base = baseName(displayName)
        val ext = extensionOf(displayName)
        var i = 1
        while ("${base}_$i$ext" in taken) i++
        return "${base}_$i$ext"
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx)
                }
            }
        return uri.lastPathSegment?.substringAfterLast('/')
    }

    private fun mimeFor(name: String): String = when {
        name.endsWith(".png", true) -> "image/png"
        name.endsWith(".webp", true) -> "image/webp"
        name.endsWith(".gif", true) -> "image/gif"
        else -> "image/jpeg"
    }
}
