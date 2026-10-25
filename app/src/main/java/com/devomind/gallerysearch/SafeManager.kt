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
import org.json.JSONObject
import java.io.File
import javax.crypto.SecretKey

/**
 * Session + storage orchestration for the encrypted photo locker.
 *
 * All safe photos live inside ONE standard AES-256 zip — the encrypted "folder" — stored at a fixed
 * public path (`Pictures/Pixa Safe/PixaSafe.zip`). With All-files access the app reads/writes
 * that file directly (zip4j needs a real path), so there is no folder picker and no session mirror:
 * the archive *is* the storage. It survives uninstall and opens in any zip tool with the password.
 *
 * The path is resolved, not assumed: [masterZip] takes the first archive that actually exists among
 * the current names and the `Deepix Safe`/`DeepixSafe.zip` ones every vault predating the rename was
 * written under. A vault the app fails to find is not an empty Safe — it is the only copy of the
 * photos already removed from the gallery, so discovery, not a rename, is what carries them over.
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

    enum class SetupOutcome { CREATED, ADOPTED, WRONG_PASSWORD, ARCHIVE_UNREADABLE, NO_ACCESS }

    private const val MasterName = "PixaSafe.zip"
    private const val VaultFolderName = "Pixa Safe"

    /** What every vault created before the app was Pixa is named. Nothing is created under them, but
     *  an archive already sitting there is opened and appended to — it is the only copy those photos
     *  have. Settings › SAFE location is the one path that renames it. */
    private const val LegacyMasterName = "DeepixSafe.zip"
    private const val LegacyVaultFolderName = "Deepix Safe"
    private val zipNames = listOf(MasterName, LegacyMasterName)
    private const val FolderMarker = ".dpixkeep"

    @Volatile private var sessionPassword: String? = null
    @Volatile private var thumbKey: SecretKey? = null
    @Volatile private var originalPathsCache: MutableMap<String, String>? = null

    private val workGuard = SafeWorkGuard()

    val isUnlocked: Boolean get() = sessionPassword != null
    fun currentPassword(): String? = sessionPassword

    /**
     * Locks the vault and wipes the plaintext staging dirs — unless a vault write is mid-flight, in
     * which case the request is parked and that write's own completion performs it. Wiping under a
     * running import is what used to truncate an archive entry.
     */
    fun lock(context: Context? = null) {
        if (!workGuard.requestLock()) return
        applyLock(context)
    }

    private fun applyLock(context: Context?) {
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

    /** Runs one archive mutation with the lock held off until it lands. */
    private fun <T> guarded(context: Context, body: () -> T): T {
        workGuard.begin()
        try {
            return body()
        } finally {
            if (workGuard.end()) applyLock(context)
        }
    }

    // ---- Original gallery locations (vault entry name -> absolute source dir) ----

    private const val OriginalPathsFile = "safe_original_paths.json"

    /**
     * Remembers where each imported photo lived before the Safe swallowed it, so "Save back to
     * gallery" restores it to its original folder instead of a fixed Deepix folder. Keyed by vault
     * entry name (the URI dies with the original). Survives lock/unlock and process death.
     */
    private fun originalPaths(context: Context): MutableMap<String, String> {
        originalPathsCache?.let { return it }
        synchronized(this) {
            originalPathsCache?.let { return it }
            val map = runCatching {
                val obj = JSONObject(File(context.filesDir, OriginalPathsFile).readText())
                val m = linkedMapOf<String, String>()
                for (key in obj.keys()) m[key] = obj.optString(key)
                m
            }.getOrDefault(linkedMapOf())
            originalPathsCache = map
            return map
        }
    }

    private fun saveOriginalPaths(context: Context) {
        val map = originalPathsCache ?: return
        runCatching {
            val obj = JSONObject()
            for ((key, value) in map) obj.put(key, value)
            File(context.filesDir, OriginalPathsFile).writeText(obj.toString())
        }
    }

    /**
     * Absolute directory the photo lived in before import. Prefers the volume-aware absolute DATA
     * path so SD-card photos remember their real volume; RELATIVE_PATH (primary-storage-relative)
     * is only a fallback and assumes primary storage.
     */
    private fun sourceOriginalDir(context: Context, source: Uri): String? {
        MediaFileOps.resolvePath(context, source)?.let { return File(it).parent }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return runCatching {
                context.contentResolver
                    .query(source, arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
                    ?.let { rel ->
                        File(Environment.getExternalStorageDirectory(), rel.trimEnd('/')).absolutePath
                    }
            }.getOrNull()
        }
        return null
    }

    // ---- Storage location (public folder, direct file I/O) ----

    /** Roots the vault can live under; the folder survives uninstall and opens in any zip tool. */
    private val knownRoots = listOf(IndexPreferences.SAFE_ROOT_PICTURES, IndexPreferences.SAFE_ROOT_DOCUMENTS)

    private fun baseDir(root: String, folderName: String = VaultFolderName): File =
        File(
            Environment.getExternalStoragePublicDirectory(
                if (root == IndexPreferences.SAFE_ROOT_DOCUMENTS) Environment.DIRECTORY_DOCUMENTS
                else Environment.DIRECTORY_PICTURES
            ),
            folderName
        )

    /**
     * The folders the vault could live in, current name first. Both names are then searched *inside*
     * each one, because the folder the user picked is theirs: a `DeepixSafe.zip` that sits in a custom
     * directory is still the vault, and inventing an empty `PixaSafe.zip` beside it would make the app
     * forget every photo it already holds.
     */
    private fun vaultDirs(context: Context): List<File> {
        val custom = IndexPreferences.getSafeVaultDir(context)
        if (!custom.isNullOrBlank()) return listOf(File(custom))
        val root = IndexPreferences.getSafeStorageRoot(context)
        return listOf(baseDir(root), baseDir(root, LegacyVaultFolderName))
    }

    /** The folder a *new* vault is created in — the current name, never the legacy one. */
    private fun newVaultDir(context: Context): File = vaultDirs(context).first()

    /**
     * The archive itself: the first candidate [vaultDirs] offers that exists, so a vault written under
     * the old names keeps opening. When nothing exists this is the creation path — the current names,
     * in the current folder or the custom one the user chose.
     */
    private fun masterZip(context: Context): File {
        for (dir in vaultDirs(context)) {
            for (name in zipNames) {
                val candidate = File(dir, name)
                if (candidate.exists()) return candidate
            }
        }
        return File(newVaultDir(context), MasterName)
    }

    /** Public root the vault falls back to when no custom folder is set (the default). */
    fun defaultVaultDir(context: Context): File = baseDir(IndexPreferences.SAFE_ROOT_PICTURES)

    /**
     * Converts a SAF tree Uri into a real directory on primary storage — the app already requires
     * All-files access for the Safe, so once the user picks a folder we can keep using direct file
     * I/O (zip4j needs a real path). Trees on other volumes are rejected.
     */
    fun customDirFromTreeUri(uri: Uri): File? {
        val docId = try {
            android.provider.DocumentsContract.getTreeDocumentId(uri)
        } catch (e: Exception) {
            return null
        }
        val parts = docId.split(":", limit = 2)
        if (parts.size != 2 || parts[0] != "primary") return null
        return File(Environment.getExternalStorageDirectory(), parts[1].trim('/'))
    }

    /** Moves the vault zip to [newDir] (created as needed). Returns false only when an existing
     *  vault couldn't be moved or the target already holds one. */
    fun moveVaultToDir(context: Context, newDir: File): Boolean {
        val oldZip = masterZip(context)
        if (!oldZip.exists() || oldZip.parentFile == newDir) return true
        newDir.mkdirs()
        val newZip = File(newDir, MasterName)
        if (newZip.exists()) return false // don't clobber a vault already at the target
        return if (oldZip.renameTo(newZip)) {
            true
        } else {
            runCatching {
                oldZip.copyTo(newZip, overwrite = false)
                if (newZip.length() != oldZip.length()) {
                    newZip.delete()
                    false
                } else if (oldZip.delete()) {
                    true
                } else {
                    newZip.delete()
                    false
                }
            }.getOrDefault(false)
        }
    }

    /**
     * Hard reset: deletes the encrypted vault everywhere it could live — every known root, under both
     * the current and the legacy names, plus the custom folder — along with app config and the
     * thumbnail cache. Use only when the password is lost — everything inside the vault is erased.
     */
    fun purgeVault(context: Context) {
        // The old names are erased as thoroughly as the new ones: this is the forgotten-password
        // escape, and a DeepixSafe.zip left on disk would be found again by archiveExists, offering
        // the same locked vault the user just chose to wipe.
        val dirs = knownRoots.flatMap { listOf(baseDir(it), baseDir(it, LegacyVaultFolderName)) } +
            IndexPreferences.getSafeVaultDir(context)?.let { listOf(File(it)) }.orEmpty()
        dirs.distinct().forEach { dir ->
            zipNames.forEach { name -> runCatching { File(dir, name).delete() } }
            // Remove the folder only if our vault was its sole content; never wipes a shared
            // folder (File.delete() on a non-empty dir would fail anyway).
            runCatching { dir.takeIf { it.list()?.isEmpty() == true }?.delete() }
        }
        IndexPreferences.setSafeVaultDir(context, null)
        runCatching { File(context.filesDir, OriginalPathsFile).delete() }
        originalPathsCache = null
        SafeStore.reset(context)
        applyLock(context)
        runCatching { File(context.filesDir, "safe_thumbs").deleteRecursively() }
    }

    // ---- Configuration ----

    fun hasAccess(context: Context): Boolean = StoragePermissions.hasAllFilesAccess(context)

    fun isConfigured(context: Context): Boolean =
        SafeStore.isConfigured(context) && hasAccess(context)

    /** True when an encrypted archive already exists on disk (recovery/adopt path). */
    fun archiveExists(context: Context): Boolean {
        val zip = masterZip(context)
        return (SafeCrypto.readArchive(zip) as? SafeCrypto.ArchiveRead.Ok)?.names?.isNotEmpty() == true
    }

    /**
     * First-time setup / recovery. Requires All-files access. Creates the fixed vault folder and,
     * if an archive is already present (reinstall or a copied vault), verifies the password against
     * it and adopts it; otherwise starts fresh. Nothing is persisted on a wrong password.
     */
    fun setUpVault(context: Context, password: String): SetupOutcome {
        if (!hasAccess(context)) return SetupOutcome.NO_ACCESS
        val zip = masterZip(context).also { it.parentFile?.mkdirs() }
        val archive = SafeCrypto.readArchive(zip)
        if (archive is SafeCrypto.ArchiveRead.Unreadable) return SetupOutcome.ARCHIVE_UNREADABLE
        val hasExisting = (archive as? SafeCrypto.ArchiveRead.Ok)?.names?.isNotEmpty() == true
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
        if (SafeCrypto.readArchive(masterZip(context)) is SafeCrypto.ArchiveRead.Unreadable) return false
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
        // The user is back inside the vault; a lock parked behind a finishing import must not eject them.
        workGuard.cancelPendingLock()
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
        return guarded(context) {
            runCatching {
                val work = File(context.cacheDir, "safe_work").apply { mkdirs() }
                val marker = File(work, FolderMarker).apply { writeText("") }
                SafeCrypto.addFileToZip(masterZip(context), marker, markerEntry, password.toCharArray())
                marker.delete()
                true
            }.getOrDefault(false)
        }
    }

    // ---- Import ----

    fun importPhotos(
        context: Context,
        sources: List<Uri>,
        folderPath: String = "",
        onProgress: ((done: Int, total: Int) -> Unit)? = null
    ): ImportResult {
        val password = sessionPassword ?: return ImportResult(0, sources.size, emptyList())
        return guarded(context) { importIntoVault(context, sources, folderPath, password, onProgress) }
    }

    private fun importIntoVault(
        context: Context,
        sources: List<Uri>,
        folderPath: String,
        password: String,
        onProgress: ((done: Int, total: Int) -> Unit)?
    ): ImportResult {
        val zip = masterZip(context).also { it.parentFile?.mkdirs() }
        val archive = SafeCrypto.readArchive(zip)
        if (archive is SafeCrypto.ArchiveRead.Unreadable) return ImportResult(0, sources.size, emptyList())
        val existing = (archive as? SafeCrypto.ArchiveRead.Ok)?.names.orEmpty().toMutableSet()
        val originalDirs = originalPaths(context)
        val work = File(context.cacheDir, "safe_work").apply { mkdirs() }
        val replacement = File(zip.parentFile, "${zip.name}.tmp")
        replacement.delete()
        try {
            if (zip.exists()) zip.copyTo(replacement, overwrite = true)
        } catch (_: Exception) {
            replacement.delete()
            return ImportResult(0, sources.size, emptyList())
        }
        var imported = 0
        var failed = 0
        var originalDirsDirty = false
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
                val staged = plain.length()
                if (staged <= 0L) throw IllegalStateException("Nothing staged for $source")

                SafeCrypto.addFileToZip(replacement, plain, entryName, password.toCharArray())
                // Read the entry back before the source can be deleted: the archive is the only copy
                // from here on, and an interrupted append looks exactly like a successful add.
                if (SafeCrypto.readEntrySize(replacement, entryName, password.toCharArray()) != staged) {
                    throw IllegalStateException("Entry $entryName did not read back whole")
                }
                existing.add(entryName)
                sourceOriginalDir(context, source)?.let {
                    originalDirs[entryName] = it
                    originalDirsDirty = true
                }

                SafeCrypto.makeThumbnailJpeg(context, source)?.let { writeThumb(context, entryName, it) }
                imported++
                importedSources.add(source)
            } catch (e: Exception) {
                failed++
            } finally {
                plain.delete()
            }
        }
        if (imported > 0) {
            try {
                SafeCrypto.replaceAtomically(replacement, zip)
            } catch (_: Exception) {
                replacement.delete()
                return ImportResult(0, sources.size, emptyList())
            }
        } else {
            replacement.delete()
        }
        if (originalDirsDirty) saveOriginalPaths(context)
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

    /** Decrypts back into the gallery — into the folder the photo originally came from when we
     *  know it, else the Deepix folder. Returns the new media uri or null. */
    fun restoreToGallery(context: Context, item: VaultItem): Uri? {
        val temp = runCatching { decryptToTemp(context, item) }.getOrNull() ?: return null
        val originalDir = originalPaths(context)[item.entryName]
        val qPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val target = if (qPlus) restoreTarget(context, originalDir) else null
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, temp.name)
                put(MediaStore.Images.Media.MIME_TYPE, mimeFor(temp.name))
                if (qPlus) {
                    put(MediaStore.Images.Media.RELATIVE_PATH, target!!.second)
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                } else if (originalDir != null) {
                    put(MediaStore.MediaColumns.DATA, File(originalDir, temp.name).absolutePath)
                }
            }
            val collection = if (qPlus) {
                MediaStore.Images.Media.getContentUri(target!!.first)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val out = context.contentResolver.insert(collection, values) ?: return null
            try {
                context.contentResolver.openOutputStream(out)?.use { os ->
                    temp.inputStream().use { it.copyTo(os) }
                } ?: throw java.io.IOException("No output stream for $out")
                if (qPlus) {
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    context.contentResolver.update(out, values, null, null)
                }
                out
            } catch (e: Exception) {
                // Never leave a broken 0-byte row behind looking like a restored photo.
                runCatching { context.contentResolver.delete(out, null, null) }
                null
            }
        } finally {
            temp.parentFile?.deleteRecursively()
        }
    }

    /**
     * Where [restoreToGallery] should write on Q+: the MediaStore volume plus its RELATIVE_PATH
     * (relative to that volume's own root, not primary's). A photo imported from an SD card
     * restores back onto that card; unknown, volume-root and unresolvable paths fall back to the
     * primary Pictures/Deepix folder.
     */
    private fun restoreTarget(context: Context, originalDir: String?): Pair<String, String> {
        val primaryRoot = Environment.getExternalStorageDirectory().absolutePath.trimEnd('/')
        if (originalDir != null) {
            val dir = originalDir.trimEnd('/')
            if (dir.startsWith(primaryRoot)) {
                val rel = dir.removePrefix(primaryRoot).trim('/')
                if (rel.isNotBlank()) return MediaStore.VOLUME_EXTERNAL_PRIMARY to "$rel/"
            } else {
                // Secondary volume (SD card). getDirectory() needs API 30; on API 29 the
                // NoSuchMethodError is swallowed and we fall back rather than crash.
                runCatching {
                    val sm = context.getSystemService(Context.STORAGE_SERVICE)
                        as android.os.storage.StorageManager
                    for (v in sm.storageVolumes) {
                        val volRoot = v.directory?.absolutePath?.trimEnd('/') ?: continue
                        if (!dir.startsWith(volRoot)) continue
                        val rel = dir.removePrefix(volRoot).trim('/')
                        val name = if (v.isPrimary) MediaStore.VOLUME_EXTERNAL_PRIMARY else v.uuid
                        if (rel.isNotBlank() && name != null) return name to "$rel/"
                    }
                }
            }
        }
        return MediaStore.VOLUME_EXTERNAL_PRIMARY to "Pictures/Deepix/"
    }

    fun removeItem(context: Context, item: VaultItem): Boolean {
        val password = sessionPassword ?: return false
        // zip4j rewrites the whole archive to drop one entry, so this is the other write a lock must
        // not be allowed to stand on top of.
        val removed = guarded(context) {
            runCatching {
                SafeCrypto.removeEntry(masterZip(context), item.entryName, password.toCharArray())
                thumbFile(context, item.entryName).delete()
                true
            }.getOrDefault(false)
        }
        if (removed) {
            originalPaths(context).remove(item.entryName)
            saveOriginalPaths(context)
        }
        return removed
    }

    // ---- Helpers ----

    private fun thumbDir(context: Context): File =
        File(context.filesDir, "safe_thumbs").apply { mkdirs() }

    /** Name embeds the thumb size so quality bumps invalidate stale blobs by never matching. */
    private fun thumbFile(context: Context, entryName: String): File =
        File(thumbDir(context), safeHash(entryName) + ".t" + SafeCrypto.ThumbMaxPx)

    /**
     * Deletes thumbnail blobs left by older naming schemes or quality levels — they can never be
     * served under the current naming and would only orphan disk. Cheap; called on Safe open.
     */
    fun cleanupLegacyThumbs(context: Context) {
        runCatching {
            File(context.filesDir, "safe_thumbs").listFiles()?.forEach { f ->
                if (!f.name.endsWith(".t" + SafeCrypto.ThumbMaxPx)) f.delete()
            }
        }
    }

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

    private fun mimeFor(name: String): String = MediaFormats.imageMimeFor(name)
}
