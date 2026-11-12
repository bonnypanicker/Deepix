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
 * All safe photos live inside ONE standard AES-256 zip — the encrypted "folder". Where that zip
 * lives is [VaultStorage]'s business, not this class's: `Documents/PixaSafe/` by default, app
 * storage, a folder the user picked, or the single file the user picked after a reinstall. Either
 * way this class only ever touches the local [File] that storage exposes, because zip4j rewrites the
 * whole archive to add or drop a single entry.
 *
 * An archive the app fails to find is not an empty Safe — it is the only copy of the photos already
 * removed from the gallery, so discovery, not a rename, is what carries a vault over to a new home.
 *
 * Sub-folders are modelled as path prefixes inside the archive (`Trip/beach.jpg`); an empty folder
 * keeps a `.dpixkeep` marker entry so it still lists, and the map of where each photo came from
 * lives inside the archive too (`.pixa/original_paths.json`) so a restore keeps its folders after a
 * reinstall. Both are hidden from listings. Extracted plaintext previews go to cache temp dirs wiped
 * after use. The unlocked password is held in memory for the life of an unlocked Safe.
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

    /** The map lives inside the archive, so a restore keeps its folders across a reinstall. */
    private const val OriginalPathsEntry = ".pixa/original_paths.json"

    /** Paths starting with this are the vault's own bookkeeping and never show up in a listing. */
    private const val HiddenPrefix = ".pixa/"

    /** Where the map sat as a plain app-storage file before it moved into the archive. */
    private const val OriginalPathsFile = "safe_original_paths.json"

    @Volatile private var originalPathsNeedMigration = false

    /**
     * Remembers where each imported photo lived before the Safe swallowed it, so "Save back to
     * gallery" restores it to its original folder instead of a fixed Deepix folder. Keyed by vault
     * entry name (the URI dies with the original). Survives lock/unlock and process death.
     *
     * Read from the archive, which is what makes it survive an uninstall. A vault made before the map
     * moved in still keeps it in app storage; that copy is read as a fallback and replaced the next
     * time the map is written.
     */
    private fun originalPaths(context: Context, password: String): MutableMap<String, String> {
        originalPathsCache?.let { return it }
        synchronized(this) {
            originalPathsCache?.let { return it }
            val map = readOriginalPaths(context, password)
            if (map.isEmpty()) {
                legacyOriginalPaths(context)?.let {
                    originalPathsNeedMigration = true
                    map.putAll(it)
                }
            }
            originalPathsCache = map
            return map
        }
    }

    private fun readOriginalPaths(context: Context, password: String): MutableMap<String, String> {
        val work = File(context.cacheDir, "safe_paths_${System.nanoTime()}")
        return try {
            val entry = SafeCrypto.extractEntry(
                archive(context), OriginalPathsEntry, password.toCharArray(), work
            )
            parsePaths(entry.readText())
        } catch (_: Exception) {
            linkedMapOf()
        } finally {
            runCatching { work.deleteRecursively() }
        }
    }

    /** The pre-archive map, if this install still has one. */
    private fun legacyOriginalPaths(context: Context): MutableMap<String, String>? {
        val file = File(context.filesDir, OriginalPathsFile)
        if (!file.exists()) return null
        return runCatching { parsePaths(file.readText()) }.getOrNull()
    }

    private fun parsePaths(json: String): MutableMap<String, String> {
        val obj = JSONObject(json)
        val map = linkedMapOf<String, String>()
        for (key in obj.keys()) map[key] = obj.optString(key)
        return map
    }

    /**
     * Writes the map into [zip] as one encrypted entry, replacing any earlier copy, and drops the
     * plaintext file it may have come from. Called on the archive being built for a commit, so the
     * map lands in the same verified write as the change that made it stale.
     */
    private fun writeOriginalPaths(context: Context, zip: File, password: String) {
        val map = originalPathsCache ?: return
        val obj = JSONObject()
        for ((key, value) in map) obj.put(key, value)
        val work = File(context.cacheDir, "safe_work").apply { mkdirs() }
        val temp = File(work, "original_paths.json").apply { writeText(obj.toString()) }
        try {
            // zip4j appends rather than replaces, so an earlier copy goes first.
            runCatching { SafeCrypto.removeEntry(zip, OriginalPathsEntry, password.toCharArray()) }
            SafeCrypto.addFileToZip(zip, temp, OriginalPathsEntry, password.toCharArray())
            runCatching { File(context.filesDir, OriginalPathsFile).delete() }
            originalPathsNeedMigration = false
        } finally {
            temp.delete()
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

    // ---- Storage location ----

    /** The vault's current home — Documents by default, app storage, or wherever the user picked. */
    private fun storage(context: Context): VaultStorage = VaultStores.of(context)

    /**
     * The local archive [SafeCrypto] works on, refreshed from its storage location first. A read
     * uses this directly; a mutation follows it with [VaultStorage.commit], which is also what
     * decides when the gallery originals may be deleted.
     */
    private fun archive(context: Context): File {
        val storage = storage(context)
        storage.refresh()
        return storage.localFile()
    }

    /**
     * Moves the vault to [location] — a picked folder needs its [treeUri] — and persists the choice.
     * The archive is copied first and the old copy dropped only once the new one is verified, so a
     * failed move leaves the vault exactly where it was. Returns false only when the target already
     * holds a vault — nothing is ever overwritten.
     */
    fun relocateVault(
        context: Context,
        location: VaultLocation,
        treeUri: Uri? = null
    ): Boolean {
        if (location == VaultLocation.PICKED && treeUri == null) return false
        val samePlace = VaultStores.currentLocation(context) == location &&
            (location != VaultLocation.PICKED || SafeStore.getTreeUri(context) == treeUri)
        if (samePlace) return true

        val old = storage(context).also { it.refresh() }
        val oldFile = old.localFile()
        val target = VaultStores.of(context, location, treeUri)
        if (!oldFile.exists()) {
            persistLocation(context, location, treeUri)
            return true
        }
        if (target.exists()) return false
        val targetFile = target.localFile()
        val moved = runCatching {
            oldFile.copyTo(targetFile, overwrite = true)
            if (targetFile.length() != oldFile.length()) return@runCatching false
            target.commit()
            true
        }.getOrDefault(false)
        if (!moved) {
            runCatching { targetFile.delete() }
            return false
        }
        persistLocation(context, location, treeUri)
        runCatching { old.delete() }
        return true
    }

    /**
     * Records where the vault now lives. A uri only means anything for the location that uses it —
     * a picked folder for [VaultLocation.PICKED], a picked file for [VaultLocation.PICKED_DOCUMENT]
     * — so leaving one of those clears the uri instead of leaving a stale grant behind.
     */
    private fun persistLocation(
        context: Context,
        location: VaultLocation,
        treeUri: Uri?,
        documentUri: Uri? = null
    ) {
        SafeStore.setVaultLocation(context, location)
        SafeStore.setTreeUri(context, if (location == VaultLocation.PICKED) treeUri else null)
        SafeStore.setDocumentUri(
            context,
            if (location == VaultLocation.PICKED_DOCUMENT) documentUri else null
        )
    }

    /**
     * Hard reset: deletes the encrypted vault from wherever it lives, plus every app-private copy,
     * app config and the thumbnail cache. Use only when the password is lost — everything inside
     * the vault is erased.
     */
    fun purgeVault(context: Context) {
        runCatching { storage(context).delete() }
        VaultStores.purgeLocal(context)
        runCatching { File(context.filesDir, OriginalPathsFile).delete() }
        originalPathsCache = null
        originalPathsNeedMigration = false
        SafeStore.reset(context)
        applyLock(context)
        runCatching { File(context.filesDir, "safe_thumbs").deleteRecursively() }
    }

    // ---- Configuration ----

    /**
     * Safe is gated behind [FeatureFlags.SAFE_ENABLED], which is on for this release. The vault
     * needs no storage permission: by default it is a zip in the public `Documents/PixaSafe/`
     * folder, and it can also be a file in app storage, a folder the user picked, or the one
     * document the user picked to restore a Safe after a reinstall.
     */
    val hasAccess: Boolean get() = FeatureFlags.SAFE_ENABLED

    fun isConfigured(context: Context): Boolean =
        SafeStore.isConfigured(context) && hasAccess

    /** True when an encrypted archive already exists at the vault's location (recovery/adopt path). */
    fun archiveExists(context: Context): Boolean {
        val zip = archive(context)
        return (SafeCrypto.readArchive(zip) as? SafeCrypto.ArchiveRead.Ok)?.names?.isNotEmpty() == true
    }

    /**
     * True when a Safe is configured but its archive is not at the vault's location — a picked
     * folder or file the user moved or stopped granting, or the shape an upgrade out of the build
     * that kept it in shared storage by path leaves behind. The photos are not gone; the file is
     * simply elsewhere, so the Safe offers to bring it back instead of showing an empty vault. Reads
     * the storage location, so call it off the main thread.
     */
    fun archiveMissing(context: Context): Boolean =
        isConfigured(context) && !archive(context).exists()

    /**
     * First-time setup / recovery. Requires the Safe to be enabled. Creates the vault archive and,
     * if one is already present (reinstall or a copied vault), verifies the password against it and
     * adopts it; otherwise starts fresh. Nothing is persisted on a wrong password.
     */
    fun setUpVault(context: Context, password: String): SetupOutcome {
        if (!hasAccess) return SetupOutcome.NO_ACCESS
        val zip = archive(context)
        val read = SafeCrypto.readArchive(zip)
        if (read is SafeCrypto.ArchiveRead.Unreadable) return SetupOutcome.ARCHIVE_UNREADABLE
        val hasExisting = (read as? SafeCrypto.ArchiveRead.Ok)?.names?.isNotEmpty() == true
        if (hasExisting && !SafeCrypto.verifyPassword(zip, password.toCharArray())) {
            return SetupOutcome.WRONG_PASSWORD
        }
        SafeStore.savePasswordVerifier(context, password)
        if (!hasExisting) {
            // A created Safe holds an (empty) archive from the start, so "configured" always implies
            // a file at the vault's location and an absent one means the vault is genuinely missing.
            runCatching {
                SafeCrypto.createEmptyArchive(zip)
                storage(context).commit()
            }
        }
        openSession(context, password)
        return if (hasExisting) SetupOutcome.ADOPTED else SetupOutcome.CREATED
    }

    /**
     * Adopts a vault archive the user picked with the system document picker — how a Safe comes back
     * after a reinstall, when the new install cannot list the previous install's rows but a document
     * the user granted persistently is always reachable.
     *
     * The picked file *becomes* the vault: its own storage keeps a mirror in app storage, and the
     * document itself is only ever written by a later commit. The picked document is copied to a
     * staging file beside that mirror and verified against [password] before anything is swapped in,
     * and the vault's home is recorded only after that check, so a wrong password or an unreadable
     * file leaves both the app's configuration and the user's own file exactly as they were.
     */
    fun adoptArchive(context: Context, source: Uri, password: String): SetupOutcome {
        if (!hasAccess) return SetupOutcome.NO_ACCESS
        val store = PickedDocumentVaultStorage(context, source)
        val target = store.localFile()
        val staged = File(target.parentFile, "${target.name}.adopt")
        staged.delete()
        val copied = runCatching {
            context.contentResolver.openInputStream(source)?.use { input ->
                staged.outputStream().use { input.copyTo(it) }
            } != null
        }.getOrDefault(false)
        if (!copied || staged.length() <= 0L) {
            staged.delete()
            return SetupOutcome.ARCHIVE_UNREADABLE
        }
        val names = (SafeCrypto.readArchive(staged) as? SafeCrypto.ArchiveRead.Ok)?.names.orEmpty()
        if (names.isEmpty()) {
            staged.delete()
            return SetupOutcome.ARCHIVE_UNREADABLE
        }
        if (!SafeCrypto.verifyPassword(staged, password.toCharArray())) {
            staged.delete()
            return SetupOutcome.WRONG_PASSWORD
        }
        val adopted = runCatching { SafeCrypto.replaceAtomically(staged, target) }.isSuccess
        staged.delete()
        if (!adopted) return SetupOutcome.ARCHIVE_UNREADABLE
        // Verified — only now does the picked file become where the app keeps the vault.
        persistLocation(context, VaultLocation.PICKED_DOCUMENT, treeUri = null, documentUri = source)
        SafeStore.setVaultDirty(context, false)
        SafeStore.savePasswordVerifier(context, password)
        openSession(context, password)
        return SetupOutcome.ADOPTED
    }

    /**
     * Opens a session for a candidate password. Verifies against the stored verifier when present,
     * otherwise against the archive itself (recovery), then (re)saves the verifier.
     */
    fun unlock(context: Context, password: String): Boolean {
        val zip = archive(context)
        if (SafeCrypto.readArchive(zip) is SafeCrypto.ArchiveRead.Unreadable) return false
        val ok = if (SafeStore.isConfigured(context)) {
            SafeStore.verifyPassword(context, password)
        } else {
            SafeCrypto.verifyPassword(zip, password.toCharArray())
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
        val names = SafeCrypto.listEntryNames(archive(context))
        val folders = linkedSetOf<String>()
        val photos = mutableListOf<VaultItem>()
        for (name in names) {
            // The vault's own bookkeeping (the original-folder map) is not a child of any folder.
            if (name.startsWith(HiddenPrefix) || !name.startsWith(prefix)) continue
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
        SafeCrypto.listEntryNames(archive(context))
            .filterNot { it.startsWith(HiddenPrefix) || it.substringAfterLast('/') == FolderMarker }
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
                SafeCrypto.addFileToZip(archive(context), marker, markerEntry, password.toCharArray())
                marker.delete()
                storage(context).commit()
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
        val zip = archive(context)
        val read = SafeCrypto.readArchive(zip)
        if (read is SafeCrypto.ArchiveRead.Unreadable) return ImportResult(0, sources.size, emptyList())
        val existing = (read as? SafeCrypto.ArchiveRead.Ok)?.names.orEmpty().toMutableSet()
        val originalDirs = originalPaths(context, password)
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
                val mime = context.contentResolver.getType(source)
                val displayName = queryDisplayName(context, source)
                val isVideo = MediaFormats.isVideoMime(mime) ||
                    displayName?.let { MediaFormats.isVideoFileName(it) } == true
                val display = displayName ?: fallbackName(isVideo, mime)
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

                val thumb = if (isVideo) {
                    SafeCrypto.makeVideoThumbnailJpeg(context, source)
                } else {
                    SafeCrypto.makeThumbnailJpeg(context, source)
                }
                thumb?.let { writeThumb(context, entryName, it) }
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
                // The map of where these photos came from goes into the archive itself, before the
                // swap, so it travels with the vault and a restore after a reinstall keeps folders.
                if (originalDirsDirty || originalPathsNeedMigration) {
                    writeOriginalPaths(context, replacement, password)
                }
                SafeCrypto.replaceAtomically(replacement, zip)
                // Nothing is settled on the originals until the archive is safely at its location:
                // a commit that fails reports nothing imported, so the sources stay in the gallery.
                storage(context).commit()
            } catch (_: Exception) {
                replacement.delete()
                return ImportResult(0, sources.size, emptyList())
            }
        } else {
            replacement.delete()
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
        val zip = archive(context)
        val jpeg = if (MediaFormats.isVideoFileName(item.entryName)) {
            val work = File(context.cacheDir, "safe_thumb_${System.nanoTime()}")
            SafeCrypto.makeVideoThumbnailJpeg(zip, item.entryName, password.toCharArray(), work)
        } else {
            SafeCrypto.makeThumbnailJpeg(zip, item.entryName, password.toCharArray())
        } ?: return null
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
        return SafeCrypto.extractEntry(archive(context), item.entryName, password.toCharArray(), work)
    }

    /** Decrypts back into the gallery — into the folder the item originally came from when we
     *  know it, else the Deepix folder. Videos go to the video collection / Movies, images to the
     *  image collection / Pictures. Returns the new media uri or null. */
    fun restoreToGallery(context: Context, item: VaultItem): Uri? {
        val password = sessionPassword ?: return null
        val temp = runCatching { decryptToTemp(context, item) }.getOrNull() ?: return null
        val isVideo = MediaFormats.isVideoFileName(temp.name)
        val originalDir = originalPaths(context, password)[item.entryName]
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Its own folder first. A folder that can't be written — a removed SD card, a
                // directory the platform no longer exposes — must not lose a photo that now lives
                // only in the vault, so the Deepix folder is the fallback rather than a failure.
                insertRestored(context, temp, isVideo, restoreTarget(context, originalDir, isVideo), null)
                    ?: insertRestored(context, temp, isVideo, defaultTarget(isVideo), null)
            } else {
                insertRestored(context, temp, isVideo, null, originalDir)
            }
        } finally {
            temp.parentFile?.deleteRecursively()
        }
    }

    /**
     * Inserts [temp] into MediaStore and writes its bytes, returning the new row's uri. [target] is
     * the volume + `RELATIVE_PATH` pair on API 29+; [legacyDir] is the absolute directory the pre-Q
     * `DATA` column takes. Returns null when the insert, the write or the byte check fails — a short
     * or empty copy is deleted again rather than published as a restored photo. That check is what
     * makes dropping the vault copy safe: nothing is dropped on a copy that is not known to be whole.
     */
    private fun insertRestored(
        context: Context,
        temp: File,
        isVideo: Boolean,
        target: Pair<String, String>?,
        legacyDir: String?
    ): Uri? {
        val qPlus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, temp.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeFor(temp.name))
            if (qPlus) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, target!!.second)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else if (legacyDir != null) {
                put(MediaStore.MediaColumns.DATA, File(legacyDir, temp.name).absolutePath)
            }
        }
        val collection = if (qPlus) {
            val volume = target!!.first
            if (isVideo) MediaStore.Video.Media.getContentUri(volume)
            else MediaStore.Images.Media.getContentUri(volume)
        } else {
            if (isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val out = runCatching { context.contentResolver.insert(collection, values) }.getOrNull()
            ?: return null
        return try {
            val written = context.contentResolver.openOutputStream(out)?.use { os ->
                temp.inputStream().use { it.copyTo(os) }
            } ?: throw java.io.IOException("No output stream for $out")
            if (written != temp.length()) throw java.io.IOException("Short write for $out")
            if (qPlus) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(out, values, null, null)
            }
            out
        } catch (e: Exception) {
            // Never leave a broken 0-byte row behind looking like a restored file.
            runCatching { context.contentResolver.delete(out, null, null) }
            null
        }
    }

    /**
     * Where [restoreToGallery] should write on Q+: the MediaStore volume plus its RELATIVE_PATH
     * (relative to that volume's own root, not primary's). A file imported from an SD card
     * restores back onto that card; unknown, volume-root and unresolvable paths fall back to the
     * primary Pictures/Deepix (images) or Movies/Deepix (videos) folder.
     */
    private fun restoreTarget(context: Context, originalDir: String?, isVideo: Boolean): Pair<String, String> {
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
        return defaultTarget(isVideo)
    }

    /** The app's own gallery folder for a restored item MediaStore can't put back where it was. */
    private fun defaultTarget(isVideo: Boolean): Pair<String, String> =
        MediaStore.VOLUME_EXTERNAL_PRIMARY to if (isVideo) "Movies/Deepix/" else "Pictures/Deepix/"

    fun removeItem(context: Context, item: VaultItem): Boolean {
        val password = sessionPassword ?: return false
        // zip4j rewrites the whole archive to drop one entry, so this is the other write a lock must
        // not be allowed to stand on top of.
        return guarded(context) {
            runCatching {
                val zip = archive(context)
                val paths = originalPaths(context, password)
                SafeCrypto.removeEntry(zip, item.entryName, password.toCharArray())
                // The map lives in the archive, so the dropped entry has to leave it in the same
                // write that settles the removal — otherwise a later restore re-creates its folder.
                if (paths.remove(item.entryName) != null || originalPathsNeedMigration) {
                    writeOriginalPaths(context, zip, password)
                }
                // Settled at the vault's location before the thumbnail that mirrors it is dropped.
                storage(context).commit()
                thumbFile(context, item.entryName).delete()
                true
            }.getOrDefault(false)
        }
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

    /** Name for an import whose source exposes no display name — the extension keeps the entry
     *  recognizable as image or video when the vault later has only the name to go on. */
    private fun fallbackName(isVideo: Boolean, mime: String?): String {
        val ext = MediaFormats.extensionForMime(mime) ?: if (isVideo) "mp4" else "jpg"
        return "${if (isVideo) "video" else "photo"}_${System.nanoTime()}.$ext"
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

    private fun mimeFor(name: String): String = MediaFormats.mimeFor(name) ?: "image/jpeg"
}
