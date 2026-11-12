package com.devomind.gallerysearch

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.IOException

/** Names the vault archive has been written under, current first. */
internal const val VAULT_ARCHIVE_NAME = "PixaSafe.zip"
internal const val LEGACY_VAULT_ARCHIVE_NAME = "DeepixSafe.zip"

/** Suffix of the file a commit writes before swapping the archive in. */
private const val TRANSFER_SUFFIX = ".tmp"

/** Suffix of the last known-good mirror kept beside it while a push is in flight. */
private const val BACKUP_SUFFIX = ".bak"

/** App-private roots: the app-storage vault itself, and a working copy of a vault kept elsewhere. */
private const val LOCAL_VAULT_ROOT = "safe_vault"
private const val MIRROR_VAULT_ROOT = "safe_vault_mirror"

/** The public folder a Documents vault lives in, and the name of its working copy. */
private const val DOCUMENTS_FOLDER = "PixaSafe"
private const val DOCUMENTS_RELATIVE_PATH = "Documents/$DOCUMENTS_FOLDER/"
private const val DOCUMENTS_MIRROR_DIR = "documents"

/** Sub-directory of the mirror root holding a picked-document vault's working copy. */
private const val DOCUMENT_MIRROR_DIR = "document_"

/** Every vault archive is a zip. */
private const val ZIP_MIME = "application/zip"

/** Names an archive may be found under, and the transfer an interrupted commit leaves behind. */
private val ArchiveNames = listOf(VAULT_ARCHIVE_NAME, LEGACY_VAULT_ARCHIVE_NAME)
private val LookupNames = ArchiveNames + (VAULT_ARCHIVE_NAME + TRANSFER_SUFFIX)

private const val TAG = "VaultStorage"

/** The homes a vault can have. [DOCUMENTS] is the default — see [VaultStores.of]. */
enum class VaultLocation { DOCUMENTS, APP_PRIVATE, PICKED, PICKED_DOCUMENT }

/**
 * Where the encrypted vault archive physically lives.
 *
 * [SafeCrypto] only understands a real [File] — zip4j rewrites the whole archive to add or drop one
 * entry — so every implementation hands out a **local** handle for it. An app-private vault *is*
 * that file; every other vault keeps a mirror in `filesDir` and moves bytes across the boundary with
 * [refresh] (before a read) and [commit] (after a mutation).
 *
 * A commit writes a transfer file, verifies its byte count and only then swaps it in, so a torn
 * transfer can never replace a good archive. Callers delete the gallery originals only once
 * [commit] has returned.
 */
interface VaultStorage {
    /** The real file [SafeCrypto] reads and rewrites. Valid after [refresh]. */
    fun localFile(): File

    /** Pulls a remote archive into [localFile] before a read. No-op when the file is local. */
    fun refresh()

    /** Pushes [localFile] to the remote after a mutation. No-op when the file is local. */
    fun commit()

    /** True when an archive already exists here. */
    fun exists(): Boolean

    /** Removes the archive, local working copy included. */
    fun delete()
}

/**
 * The rules that keep the only reachable copy of a vault alive, kept free of Android types so they
 * can be exercised directly. Every mirroring [VaultStorage] defers to this: the decisions here are
 * what stands between a failed sync and a vault the user can still open.
 */
internal object VaultMirror {

    /**
     * Whether a remote archive of a *known, different* size has to be pulled down. A missing remote
     * (null) never becomes a pull — and never a delete either: after a reinstall the mirror is the
     * only copy of the vault the app can reach, not a stale cache of one.
     */
    fun needsPull(local: File, remoteSize: Long?): Boolean =
        remoteSize != null && !(local.exists() && remoteSize >= 0 && local.length() == remoteSize)

    /**
     * Whether a push may be trusted: the destination reports the mirror's byte count and the mirror
     * still reads as an archive. A destination that will not report a size (-1) is not a failure —
     * the archive check still had to pass.
     */
    fun pushVerified(documentSize: Long, mirrorLength: Long, archiveReadable: Boolean): Boolean =
        archiveReadable && (documentSize < 0 || documentSize == mirrorLength)

    /**
     * Whether an archive landed under the name the app asked for. A provider that renames on
     * collision would leave the app looking for a file that is not there, so anything else is
     * treated as a failed commit. A provider that will not report a name at all is accepted.
     */
    fun nameLanded(landed: String?, expected: String): Boolean = landed == null || landed == expected

    /**
     * A name [VAULT_ARCHIVE_NAME] may be published under when the intended one is taken by a file
     * the app cannot list — an invisible file is never overwritten.
     */
    fun uniqueArchiveName(taken: Set<String>): String {
        val base = VAULT_ARCHIVE_NAME.removeSuffix(SafeCrypto.VaultExtension)
        var i = 2
        while (true) {
            val candidate = "$base ($i)${SafeCrypto.VaultExtension}"
            if (candidate !in taken) return candidate
            i++
        }
    }
}

/**
 * The app's own private `filesDir`, which is where the vault sits when the user has not asked for it
 * anywhere else reachable. It needs no permission and no consent dialog, and it goes away with the
 * app — [DocumentsVaultStorage] is the default, and [SafVaultStorage]/[PickedDocumentVaultStorage]
 * are how a user keeps the archive somewhere of their own.
 */
class AppPrivateVaultStorage(private val context: Context) : VaultStorage {
    private fun archive(): File = File(File(context.filesDir, LOCAL_VAULT_ROOT), VAULT_ARCHIVE_NAME)

    override fun localFile(): File = archive().also { it.parentFile?.mkdirs() }
    override fun refresh() = Unit
    override fun commit() = Unit
    override fun exists(): Boolean = archive().exists()
    override fun delete() {
        runCatching { archive().delete() }
    }
}

/**
 * The default: the archive is a zip in `Documents/PixaSafe/`, where the user can see it, copy it
 * out, or carry it to another device. MediaStore owns that row, so this needs no permission and no
 * consent dialog — the same way the app already writes into Pictures and Documents.
 *
 * A MediaStore row is not a [File] that zip4j can rewrite, so the archive is worked on as a mirror
 * in `filesDir` and moved across the boundary by [refresh]/[commit], exactly like
 * [SafVaultStorage]. Below API 29 there is no MediaStore to write through and the app still holds
 * `WRITE_EXTERNAL_STORAGE`, so the public folder is written directly and no mirror is kept.
 */
class DocumentsVaultStorage(private val context: Context) : VaultStorage {

    private val resolver get() = context.contentResolver

    /** API 29+ speaks MediaStore; below that the public folder is a plain directory. */
    private fun qPlus(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    private fun collection(): Uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun publicDir(): File =
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)

    /** The vault's own folder, so the archive never sits loose among the user's documents. */
    private fun vaultDir(): File = File(publicDir(), DOCUMENTS_FOLDER)

    /** Pre-Q: the archive is a file in the public folder, so it is also the working copy. */
    private fun directArchive(): File = File(vaultDir(), VAULT_ARCHIVE_NAME)

    private fun mirrorDir(): File = File(File(context.filesDir, MIRROR_VAULT_ROOT), DOCUMENTS_MIRROR_DIR)
    private fun mirror(): File = File(mirrorDir(), VAULT_ARCHIVE_NAME)

    /** The name this install actually published the archive under; the standard one unless renamed. */
    private fun archiveName(): String = SafeStore.getArchiveName(context) ?: VAULT_ARCHIVE_NAME

    private fun lookupNames(): List<String> = (listOf(archiveName()) + LookupNames).distinct()

    override fun localFile(): File =
        (if (qPlus()) mirror() else directArchive()).also { it.parentFile?.mkdirs() }

    /**
     * Display name -> uri for the vault rows in the vault folder, or null when the query itself
     * failed.
     *
     * Null means "could not ask", which is deliberately not "nothing there": reading a refused query
     * as an empty folder would let [refresh] throw away a mirror that is the only copy of the vault.
     */
    private fun remoteRows(): Map<String, Uri>? = runCatching {
        val names = lookupNames()
        val found = LinkedHashMap<String, Uri>()
        val selection = MediaStore.MediaColumns.DISPLAY_NAME +
            " IN (" + names.joinToString(",") { "?" } + ")"
        resolver.query(
            collection(),
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
            selection,
            names.toTypedArray(),
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            while (cursor.moveToNext()) {
                val name = cursor.getString(nameColumn) ?: continue
                found[name] = ContentUris.withAppendedId(collection(), cursor.getLong(idColumn))
            }
        }
        found
    }.getOrNull()

    /** The archive already in the vault folder, under any name it could have been left with. */
    private fun remoteArchive(rows: Map<String, Uri>?): Uri? {
        if (rows == null) return null
        for (name in lookupNames()) rows[name]?.let { return it }
        return null
    }

    private fun remoteSize(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    }.getOrDefault(-1L)

    private fun remoteDisplayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }.getOrNull()

    override fun refresh() {
        if (!qPlus()) return
        val rows = remoteRows() ?: return
        // No row means no vault to pull — not a vault to forget. The mirror stays exactly as it is;
        // it may be the only copy of the photos this install can still reach.
        val remote = remoteArchive(rows) ?: return
        val local = mirror()
        if (!VaultMirror.needsPull(local, remoteSize(remote))) return
        runCatching {
            local.parentFile?.mkdirs()
            val temp = File(local.parentFile, "${local.name}.refresh")
            resolver.openInputStream(remote)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Cannot read $remote")
            if (!temp.renameTo(local)) {
                temp.copyTo(local, overwrite = true)
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "Cannot mirror the vault archive out of Documents", it) }
    }

    override fun commit() {
        if (!qPlus()) return
        val local = mirror()
        if (!local.exists()) return
        // Without knowing what is already there a second archive could be planted beside the first.
        val rows = remoteRows() ?: throw IOException("Cannot list the vault folder")
        rows[VAULT_ARCHIVE_NAME + TRANSFER_SUFFIX]?.let { runCatching { resolver.delete(it, null, null) } }
        val transfer = resolver.insert(collection(), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, VAULT_ARCHIVE_NAME + TRANSFER_SUFFIX)
            put(MediaStore.MediaColumns.MIME_TYPE, ZIP_MIME)
            put(MediaStore.MediaColumns.RELATIVE_PATH, DOCUMENTS_RELATIVE_PATH)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw IOException("Cannot create the vault transfer file")
        try {
            resolver.openOutputStream(transfer)?.use { output ->
                local.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("Cannot write the vault transfer file")
            val landed = remoteSize(transfer)
            if (landed >= 0 && landed != local.length()) {
                throw IOException("Vault transfer landed short: $landed of ${local.length()} bytes")
            }
        } catch (e: Exception) {
            runCatching { resolver.delete(transfer, null, null) }
            throw e
        }
        // A PixaSafe.zip this install cannot list would be renamed or overwritten by MediaStore, so
        // the archive is published under a name of its own instead and that name is remembered.
        val name = archiveName()
        val target = when {
            name != VAULT_ARCHIVE_NAME -> name
            VAULT_ARCHIVE_NAME in rows.keys -> VAULT_ARCHIVE_NAME
            File(vaultDir(), VAULT_ARCHIVE_NAME).exists() -> VaultMirror.uniqueArchiveName(rows.keys)
            else -> VAULT_ARCHIVE_NAME
        }
        // The old archive goes only once the new one is known whole — the same ordering as
        // [SafVaultStorage]. An interrupted commit leaves the transfer, which [LookupNames] still finds.
        rows.forEach { (existing, uri) ->
            if (existing in ArchiveNames || existing == name) {
                runCatching { resolver.delete(uri, null, null) }
            }
        }
        val published = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, target)
            put(MediaStore.MediaColumns.IS_PENDING, 0)
        }
        if (runCatching { resolver.update(transfer, published, null, null) }.getOrDefault(0) <= 0) {
            throw IOException("Cannot name the vault archive")
        }
        if (!VaultMirror.nameLanded(remoteDisplayName(transfer), target)) {
            // The vault landed under a name the app does not look for; leaving it there would hide
            // the new archive behind the old one. The mirror still holds every photo.
            runCatching { resolver.delete(transfer, null, null) }
            throw IOException("The vault archive was published under another name")
        }
        if (target != VAULT_ARCHIVE_NAME) SafeStore.setArchiveName(context, target)
    }

    override fun exists(): Boolean =
        if (qPlus()) mirror().exists() || remoteArchive(remoteRows()) != null
        else directArchive().exists()

    override fun delete() {
        if (!qPlus()) {
            runCatching { directArchive().delete() }
            return
        }
        remoteRows()?.forEach { (_, uri) -> runCatching { resolver.delete(uri, null, null) } }
        runCatching { mirrorDir().deleteRecursively() }
        SafeStore.setArchiveName(context, null)
    }
}

/**
 * The one document the user picked with `ACTION_OPEN_DOCUMENT` and granted persistently. This is
 * how a Safe comes back after a reinstall: a new install cannot list the previous install's
 * MediaStore rows, but a file the user points at is always reachable.
 *
 * A single-document grant allows no sibling temp file, so a commit cannot swap a verified transfer
 * into place the way [DocumentsVaultStorage] does. It keeps the last known-good mirror beside it as
 * `.bak`, writes the mirror into the document, checks the byte count the document reports and that
 * the mirror still reads as an archive, and only then drops the `.bak`. A push that fails leaves the
 * mirror authoritative and marks the vault dirty, so [refresh] pushes it again on the next unlock
 * instead of pulling a half-written document back over good photos.
 */
class PickedDocumentVaultStorage internal constructor(
    private val mirrorDir: File,
    private val document: VaultDocument
) : VaultStorage {

    /** Production wiring: the document is whatever the persisted uri resolves to. */
    constructor(context: Context, uri: Uri) : this(
        File(
            File(context.filesDir, MIRROR_VAULT_ROOT),
            DOCUMENT_MIRROR_DIR + Integer.toHexString(uri.toString().hashCode())
        ),
        ResolverVaultDocument(context, uri)
    )

    private fun mirror(): File = File(mirrorDir, VAULT_ARCHIVE_NAME)
    private fun backup(): File = File(mirrorDir, VAULT_ARCHIVE_NAME + BACKUP_SUFFIX)

    override fun localFile(): File = mirror().also { it.parentFile?.mkdirs() }

    override fun refresh() {
        val local = mirror()
        // A `.bak` outlives the mirror it was copied from, and it is the archive the last good push
        // carried — restoring it is better than reporting a vault that is not actually gone.
        if (!local.exists() && backup().exists()) runCatching { backup().copyTo(local, overwrite = true) }
        if (document.dirty && local.exists()) {
            // The last push did not land: the mirror holds the photos and the document does not.
            runCatching { commit() }
            return
        }
        if (!document.exists()) return
        if (!VaultMirror.needsPull(local, document.size())) return
        runCatching { document.readTo(local) }
            .onFailure { Log.w(TAG, "Cannot mirror the vault archive out of the picked file", it) }
    }

    override fun commit() {
        val local = mirror()
        if (!local.exists()) return
        if (!document.writable()) throw IOException("The Safe file is not writable")
        runCatching { local.copyTo(backup(), overwrite = true) }
        document.dirty = true
        document.writeFrom(local)
        val readable = SafeCrypto.readArchive(local) is SafeCrypto.ArchiveRead.Ok
        if (!VaultMirror.pushVerified(document.size(), local.length(), readable)) {
            // The document did not take the whole archive. The mirror still has every photo and
            // stays authoritative; the next refresh pushes it again.
            throw IOException("The Safe file did not land whole")
        }
        document.dirty = false
        runCatching { backup().delete() }
    }

    override fun exists(): Boolean = mirror().exists() || document.exists()

    override fun delete() {
        runCatching { mirrorDir.deleteRecursively() }
        document.truncate()
        document.dirty = false
    }
}

/**
 * The single document a picked-document vault is stored in. Split out so the commit/refresh rules —
 * the ones that decide whether the only reachable copy of a vault is safe to overwrite — can be
 * exercised against a fake.
 */
internal interface VaultDocument {
    /** True while the last push did not land and the mirror is therefore authoritative. */
    var dirty: Boolean

    /** True when the document still exists and this install may read it. */
    fun exists(): Boolean

    /** The document's byte count, or -1 when the provider will not say. */
    fun size(): Long

    /** True when this install still holds a write grant for the document. */
    fun writable(): Boolean

    fun readTo(target: File)

    fun writeFrom(source: File)

    /** Empties the document, which is what deleting a picked-document vault can amount to. */
    fun truncate()
}

/** [VaultDocument] over a persisted content uri. The grant dies with the app, so a reinstall has to
 *  re-pick the file before this reports anything. */
internal class ResolverVaultDocument(
    private val context: Context,
    private val uri: Uri
) : VaultDocument {

    private val resolver get() = context.contentResolver

    private fun granted(write: Boolean): Boolean = runCatching {
        resolver.persistedUriPermissions.any { it.uri == uri && (if (write) it.isWritePermission else it.isReadPermission) }
    }.getOrDefault(false)

    override var dirty: Boolean
        get() = SafeStore.isVaultDirty(context)
        set(value) = SafeStore.setVaultDirty(context, value)

    override fun exists(): Boolean = granted(write = false) && runCatching {
        resolver.openInputStream(uri)?.use { it.read() >= 0 } ?: false
    }.getOrDefault(false)

    override fun size(): Long = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    }.getOrDefault(-1L)

    override fun writable(): Boolean = granted(write = true)

    override fun readTo(target: File) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.refresh")
        resolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { input.copyTo(it) }
        } ?: throw IOException("Cannot read $uri")
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
    }

    override fun writeFrom(source: File) {
        val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write $uri")
        output.use { out -> source.inputStream().use { it.copyTo(out) } }
    }

    override fun truncate() {
        runCatching { resolver.openOutputStream(uri, "wt")?.use { } }
    }
}

/**
 * A folder the user picked through `ACTION_OPEN_DOCUMENT_TREE`, held by a persisted permission.
 * zip4j cannot rewrite a document, so the archive is mirrored into `filesDir`: [refresh] pulls it
 * down before a read and [commit] pushes it back after a mutation. The mirror is keyed by the tree,
 * so two picked folders never share a working copy.
 *
 * The grant goes away with the app, so a tree the user has not re-picked since a reinstall reports
 * nothing rather than serving a mirror the app can no longer write — that is what routes the Safe to
 * its restore prompt instead of showing an empty vault.
 */
class SafVaultStorage(private val context: Context, private val treeUri: Uri) : VaultStorage {

    private val resolver get() = context.contentResolver

    private fun mirrorDir(): File =
        File(File(context.filesDir, MIRROR_VAULT_ROOT), Integer.toHexString(treeUri.toString().hashCode()))

    private fun mirror(): File = File(mirrorDir(), VAULT_ARCHIVE_NAME)

    /** A persisted tree grant dies with the app; without it the folder is out of reach. */
    private fun granted(): Boolean = runCatching {
        resolver.persistedUriPermissions.any { it.uri == treeUri }
    }.getOrDefault(false)

    private fun treeDocumentUri(): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri, DocumentsContract.getTreeDocumentId(treeUri)
    )

    /** Display name -> uri for every child of the picked folder. */
    private fun children(): Map<String, Uri> {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DocumentsContract.getTreeDocumentId(treeUri)
        )
        val found = LinkedHashMap<String, Uri>()
        resolver.query(
            childrenUri,
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            ),
            null, null, null
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0)
                val name = cursor.getString(1)
                if (id != null && name != null) {
                    found[name] = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                }
            }
        }
        return found
    }

    /**
     * The archive already in the picked folder, under any name it could have been left with — a
     * transfer abandoned by an interrupted commit included, since that file is the only copy then.
     */
    private fun remoteArchive(): Uri? {
        val children = runCatching { children() }.getOrDefault(emptyMap())
        for (name in LookupNames) children[name]?.let { return it }
        return null
    }

    private fun remoteSize(uri: Uri): Long = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_SIZE), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L } ?: -1L
    }.getOrDefault(-1L)

    private fun displayName(uri: Uri): String? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
    }.getOrNull()

    override fun localFile(): File = mirror().also { it.parentFile?.mkdirs() }

    /**
     * Copies the folder's archive down when the working copy is missing or a different length —
     * first open after a process restart, or a change made outside the app. A finished commit has
     * already left the two in step, so an unchanged vault costs one directory listing.
     */
    override fun refresh() {
        if (!granted()) return
        // No archive in the folder is not an instruction to forget one: the mirror stays as it is,
        // because it may be the only copy of the photos this install can still reach.
        val remote = remoteArchive() ?: return
        val local = mirror()
        if (!VaultMirror.needsPull(local, remoteSize(remote))) return
        runCatching {
            local.parentFile?.mkdirs()
            val temp = File(local.parentFile, "${local.name}.refresh")
            resolver.openInputStream(remote)?.use { input ->
                temp.outputStream().use { input.copyTo(it) }
            } ?: throw IOException("Cannot read $remote")
            if (!temp.renameTo(local)) {
                temp.copyTo(local, overwrite = true)
                temp.delete()
            }
        }.onFailure { Log.w(TAG, "Cannot mirror the vault archive from $remote", it) }
    }

    /**
     * Copies the working copy up: write a transfer file, check the byte count landed, drop the old
     * archive, then rename the transfer into place. The old archive is removed only once the new one
     * is complete, so the folder is never left without a vault.
     */
    override fun commit() {
        val local = mirror()
        if (!local.exists()) return
        val existing = runCatching { children() }.getOrDefault(emptyMap())
        if (existing.containsKey(VAULT_ARCHIVE_NAME + TRANSFER_SUFFIX)) {
            existing[VAULT_ARCHIVE_NAME + TRANSFER_SUFFIX]
                ?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        }
        val transfer = DocumentsContract.createDocument(
            resolver, treeDocumentUri(), "application/zip", VAULT_ARCHIVE_NAME + TRANSFER_SUFFIX
        ) ?: throw IOException("Cannot create the vault transfer file")
        try {
            resolver.openOutputStream(transfer)?.use { output ->
                local.inputStream().use { it.copyTo(output) }
            } ?: throw IOException("Cannot write the vault transfer file")
            val landed = remoteSize(transfer)
            if (landed >= 0 && landed != local.length()) {
                throw IOException("Vault transfer landed short: $landed of ${local.length()} bytes")
            }
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, transfer) }
            throw e
        }
        existing.forEach { (name, uri) ->
            if (name in ArchiveNames) runCatching { DocumentsContract.deleteDocument(resolver, uri) }
        }
        val renamed = DocumentsContract.renameDocument(resolver, transfer, VAULT_ARCHIVE_NAME)
            ?: throw IOException("Cannot finalize the vault archive")
        if (!VaultMirror.nameLanded(displayName(renamed), VAULT_ARCHIVE_NAME)) {
            // Under another name the app would never find it again, and the folder would keep a
            // full-size archive nothing refers to. The mirror still holds every photo.
            runCatching { DocumentsContract.deleteDocument(resolver, renamed) }
            throw IOException("The vault archive was finalized under another name")
        }
    }

    override fun exists(): Boolean = granted() && (mirror().exists() || remoteArchive() != null)

    override fun delete() {
        remoteArchive()?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        runCatching { mirrorDir().deleteRecursively() }
    }
}

/** Picks the storage for the vault's current location, and knows the app-private copies to sweep. */
object VaultStores {

    /** The vault as it is configured right now. */
    fun of(context: Context): VaultStorage =
        of(
            context,
            currentLocation(context),
            SafeStore.getTreeUri(context),
            SafeStore.getDocumentUri(context)
        )

    /** The vault at a specific [location]; the uris are only read for the picked locations. */
    fun of(
        context: Context,
        location: VaultLocation,
        treeUri: Uri? = SafeStore.getTreeUri(context),
        documentUri: Uri? = SafeStore.getDocumentUri(context)
    ): VaultStorage = when (location) {
        VaultLocation.PICKED -> treeUri?.let { SafVaultStorage(context, it) }
            ?: DocumentsVaultStorage(context)
        VaultLocation.PICKED_DOCUMENT -> documentUri?.let { PickedDocumentVaultStorage(context, it) }
            ?: DocumentsVaultStorage(context)
        VaultLocation.APP_PRIVATE -> AppPrivateVaultStorage(context)
        VaultLocation.DOCUMENTS -> DocumentsVaultStorage(context)
    }

    /**
     * Where the vault is living right now. A location that was never stored belongs to a build from
     * before locations existed, so it is inherited: a picked folder first, then an archive already
     * sitting in app storage — leaving either where it is rather than orphaning it behind the new
     * Documents default — and only a genuinely fresh install starts in Documents.
     */
    fun currentLocation(context: Context): VaultLocation = SafeStore.getVaultLocation(context)
        ?: when {
            SafeStore.getTreeUri(context) != null -> VaultLocation.PICKED
            AppPrivateVaultStorage(context).exists() -> VaultLocation.APP_PRIVATE
            else -> VaultLocation.DOCUMENTS
        }

    /** Removes every app-private copy of the vault — the app-storage archive and any working copy. */
    fun purgeLocal(context: Context) {
        runCatching { File(context.filesDir, LOCAL_VAULT_ROOT).deleteRecursively() }
        runCatching { File(context.filesDir, MIRROR_VAULT_ROOT).deleteRecursively() }
    }
}
