package com.devomind.gallerysearch

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.File
import javax.crypto.SecretKey

/**
 * Session + storage orchestration for the encrypted photo locker.
 *
 * All safe photos live inside ONE standard AES-256 zip — the encrypted "folder" — stored at a fixed
 * public path (`Pictures/Deepix Safe/DeepixSafe.zip`). With All-files access the app reads/writes
 * that file directly (zip4j needs a real path), so there is no folder picker and no session mirror:
 * the archive *is* the storage. It survives uninstall and opens in any zip tool with the password.
 *
 * Sub-folders are modelled as path prefixes inside the archive (`Trip/beach.jpg`); an empty folder
 * keeps a `.dpixkeep` marker entry so it still lists. Extracted plaintext previews go to cache temp
 * dirs wiped after use. The unlocked password is held in memory for the life of an unlocked Safe.
 */
object SafeManager {

    /** A photo inside the vault archive. [entryName] is its full path within the zip. */
    data class VaultItem(val entryName: String) {
        val displayName: String get() = entryName.substringAfterLast('/')
    }

    /** A sub-folder inside the vault. [path] ends with '/', e.g. "Trip/". */
    data class VaultFolder(val path: String) {
        val name: String get() = path.trimEnd('/').substringAfterLast('/')
    }

    data class FolderListing(val folders: List<VaultFolder>, val photos: List<VaultItem>)

    data class ImportResult(val imported: Int, val failed: Int, val importedSources: List<Uri>)

    enum class SetupOutcome { CREATED, ADOPTED, WRONG_PASSWORD, NO_ACCESS }

    private const val MasterName = "DeepixSafe.zip"
    private const val VaultFolderName = "Deepix Safe"
    private const val FolderMarker = ".dpixkeep"

    @Volatile private var sessionPassword: String? = null
    @Volatile private var thumbKey: SecretKey? = null

    val isUnlocked: Boolean get() = sessionPassword != null
    fun currentPassword(): String? = sessionPassword

    fun lock(context: Context? = null) {
        sessionPassword = null
        thumbKey = null
        context?.let { ctx ->
            runCatching {
                File(ctx.cacheDir, "safe_work").deleteRecursively()
                ctx.cacheDir.listFiles { f -> f.isDirectory && f.name.startsWith("safe_view_") }
                    ?.forEach { it.deleteRecursively() }
            }
        }
    }

    // ---- Storage location (public folder, direct file I/O) ----

    /** Roots the vault can live under; the folder survives uninstall and opens in any zip tool. */
    private val knownRoots = listOf(IndexPreferences.SAFE_ROOT_PICTURES, IndexPreferences.SAFE_ROOT_DOCUMENTS)

    private fun baseDir(root: String): File =
        File(
            Environment.getExternalStoragePublicDirectory(
                if (root == IndexPreferences.SAFE_ROOT_DOCUMENTS) Environment.DIRECTORY_DOCUMENTS
                else Environment.DIRECTORY_PICTURES
            ),
            VaultFolderName
        )

    private fun vaultDir(context: Context): File =
        baseDir(IndexPreferences.getSafeStorageRoot(context))

    private fun masterZip(context: Context): File = File(vaultDir(context), MasterName)

    /** Human-readable location shown to the user (for the "keep it safe" messaging). */
    fun vaultLocationLabel(context: Context): String {
        val root = IndexPreferences.getSafeStorageRoot(context)
        val parent = if (root == IndexPreferences.SAFE_ROOT_DOCUMENTS) "Documents" else "Pictures"
        return "$parent/$VaultFolderName/$MasterName"
    }

    /**
     * Moves the vault zip from [oldRoot] to [newRoot]. Returns `true` when there is nothing to move
     * or the move succeeds; `false` if an existing vault couldn't be moved or the target already
     * holds one (so the caller can abort without losing data).
     */
    fun moveVault(context: Context, oldRoot: String, newRoot: String): Boolean {
        if (oldRoot == newRoot) return true
        val oldZip = File(baseDir(oldRoot), MasterName)
        if (!oldZip.exists()) return true
        val newDir = baseDir(newRoot).apply { mkdirs() }
        val newZip = File(newDir, MasterName)
        if (newZip.exists()) return false // don't clobber a vault already at the target
        return if (oldZip.renameTo(newZip)) {
            true
        } else {
            runCatching {
                oldZip.copyTo(newZip, overwrite = false)
                oldZip.delete()
                true
            }.getOrDefault(false)
        }
    }

    /**
     * Hard reset: deletes the encrypted vault across every known root, plus app config and the
     * thumbnail cache. Use only when the password is lost — everything inside the vault is erased.
     */
    fun purgeVault(context: Context) {
        for (root in knownRoots) {
            runCatching { File(baseDir(root), MasterName).delete() }
            runCatching { baseDir(root).delete() } // removes the now-empty folder
        }
        SafeStore.reset(context)
        lock(context)
        runCatching { File(context.filesDir, "safe_thumbs").deleteRecursively() }
    }

    // ---- Configuration ----

    fun hasAccess(context: Context): Boolean = StoragePermissions.hasAllFilesAccess(context)

    fun isConfigured(context: Context): Boolean =
        SafeStore.isConfigured(context) && hasAccess(context)

    /** True when an encrypted archive already exists on disk (recovery/adopt path). */
    fun archiveExists(context: Context): Boolean {
        val zip = masterZip(context)
        return zip.exists() && SafeCrypto.listEntryNames(zip).isNotEmpty()
    }

    /**
     * First-time setup / recovery. Requires All-files access. Creates the fixed vault folder and,
     * if an archive is already present (reinstall or a copied vault), verifies the password against
     * it and adopts it; otherwise starts fresh. Nothing is persisted on a wrong password.
     */
    fun setUpVault(context: Context, password: String): SetupOutcome {
        if (!hasAccess(context)) return SetupOutcome.NO_ACCESS
        vaultDir(context).mkdirs()
        val zip = masterZip(context)
        val hasExisting = zip.exists() && SafeCrypto.listEntryNames(zip).isNotEmpty()
        if (hasExisting && !SafeCrypto.verifyPassword(zip, password.toCharArray())) {
            return SetupOutcome.WRONG_PASSWORD
        }
        SafeStore.savePasswordVerifier(context, password)
        openSession(context, password)
        return if (hasExisting) SetupOutcome.ADOPTED else SetupOutcome.CREATED
    }

    /**
     * Opens a session for a candidate password. Verifies against the stored verifier when present,
     * otherwise against the archive itself (recovery), then (re)saves the verifier.
     */
    fun unlock(context: Context, password: String): Boolean {
        val ok = if (SafeStore.isConfigured(context)) {
            SafeStore.verifyPassword(context, password)
        } else {
            SafeCrypto.verifyPassword(masterZip(context), password.toCharArray())
        }
        if (!ok) return false
        if (!SafeStore.isConfigured(context)) SafeStore.savePasswordVerifier(context, password)
        openSession(context, password)
        return true
    }

    private fun openSession(context: Context, password: String) {
        sessionPassword = password
        thumbKey = SafeStore.saltOrNull(context)?.let { SafeCrypto.thumbKey(password, it) }
    }

    // ---- Listing (folder-aware) ----

    /** Lists the immediate sub-folders and photos under [folderPath] ("" = root). */
    fun listFolder(context: Context, folderPath: String = ""): FolderListing {
        val prefix = folderPath
        val names = SafeCrypto.listEntryNames(masterZip(context))
        val folders = linkedSetOf<String>()
        val photos = mutableListOf<VaultItem>()
        for (name in names) {
            if (!name.startsWith(prefix)) continue
            val remainder = name.substring(prefix.length)
            if (remainder.isEmpty()) continue
            val slash = remainder.indexOf('/')
            if (slash >= 0) {
                folders.add(prefix + remainder.substring(0, slash + 1)) // keep trailing '/'
            } else if (remainder != FolderMarker) {
                photos.add(VaultItem(name))
            }
        }
        return FolderListing(
            folders = folders.map { VaultFolder(it) }.sortedBy { it.name.lowercase() },
            photos = photos.sortedByDescending { it.entryName }
        )
    }

    /** Flat list of every photo in the vault (used where folders don't matter). */
    fun listItems(context: Context): List<VaultItem> =
        SafeCrypto.listEntryNames(masterZip(context))
            .filterNot { it.substringAfterLast('/') == FolderMarker }
            .sortedDescending()
            .map { VaultItem(it) }

    fun createFolder(context: Context, parentPath: String, name: String): Boolean {
        val password = sessionPassword ?: return false
        val clean = name.trim().replace(Regex("[/\\\\]"), "_")
        if (clean.isEmpty()) return false
        val markerEntry = "$parentPath$clean/$FolderMarker"
        return runCatching {
            val work = File(context.cacheDir, "safe_work").apply { mkdirs() }
            val marker = File(work, FolderMarker).apply { writeText("") }
            SafeCrypto.addFileToZip(masterZip(context), marker, markerEntry, password.toCharArray())
            marker.delete()
            true
        }.getOrDefault(false)
    }

    // ---- Import ----

    fun importPhotos(
        context: Context,
        sources: List<Uri>,
        folderPath: String = "",
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ImportResult {
        val password = sessionPassword ?: return ImportResult(0, sources.size, emptyList())
        vaultDir(context).mkdirs()
        val zip = masterZip(context)
        val existing = SafeCrypto.listEntryNames(zip).toMutableSet()
        val work = File(context.cacheDir, "safe_work").apply { mkdirs() }
        var imported = 0
        var failed = 0
        val importedSources = mutableListOf<Uri>()
        for (source in sources) {
            onProgress?.invoke(imported + failed, sources.size)
            val plain = File(work, "in_${System.nanoTime()}")
            try {
                val display = queryDisplayName(context, source) ?: "photo_${System.nanoTime()}.jpg"
                val entryName = uniqueEntry(folderPath, display, existing)
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
        return ImportResult(imported, failed, importedSources)
    }

    // ---- Thumbnails (app-private encrypted cache, regenerable from the archive) ----

    fun thumbnail(context: Context, item: VaultItem): Bitmap? {
        val key = thumbKey ?: return null
        val cache = thumbFile(context, item.entryName)
        if (cache.exists()) {
            val cached = runCatching {
                val bytes = SafeCrypto.decryptBytes(key, cache.readBytes())
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
            if (cached != null) return cached
            cache.delete() // corrupt/stale — rebuild below
        }
        val password = sessionPassword ?: return null
        val jpeg = SafeCrypto.makeThumbnailJpeg(masterZip(context), item.entryName, password.toCharArray())
            ?: return null
        writeThumb(context, item.entryName, jpeg)
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
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
        return SafeCrypto.extractEntry(masterZip(context), item.entryName, password.toCharArray(), work)
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
            SafeCrypto.removeEntry(masterZip(context), item.entryName, password.toCharArray())
            thumbFile(context, item.entryName).delete()
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

    /** A collision-free entry path within [folderPath] of the archive. */
    private fun uniqueEntry(folderPath: String, displayName: String, taken: Set<String>): String {
        val base = baseName(displayName)
        val ext = extensionOf(displayName)
        var candidate = "$folderPath$displayName"
        var i = 1
        while (candidate in taken) {
            candidate = "$folderPath${base}_$i$ext"
            i++
        }
        return candidate
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
