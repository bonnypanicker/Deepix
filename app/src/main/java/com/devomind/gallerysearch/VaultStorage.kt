package com.devomind.gallerysearch

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.io.IOException

/** Names the vault archive has been written under, current first. */
internal const val VAULT_ARCHIVE_NAME = "PixaSafe.zip"
internal const val LEGACY_VAULT_ARCHIVE_NAME = "DeepixSafe.zip"

/** Suffix of the file a SAF commit writes before swapping the archive in. */
private const val TRANSFER_SUFFIX = ".tmp"

/** App-private roots: the app-storage vault itself, and a picked folder's working copy. */
private const val LOCAL_VAULT_ROOT = "safe_vault"
private const val MIRROR_VAULT_ROOT = "safe_vault_mirror"

private const val TAG = "VaultStorage"

/**
 * Where the encrypted vault archive physically lives.
 *
 * [SafeCrypto] only understands a real [File] — zip4j rewrites the whole archive to add or drop one
 * entry — so every implementation hands out a **local** handle for it. An app-private vault *is*
 * that file; a folder the user picked keeps a mirror in `filesDir` and moves bytes across the
 * boundary with [refresh] (before a read) and [commit] (after a mutation).
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
 * The default: the vault is a plain file under the app's private `filesDir`. It needs no permission
 * and no consent dialog, and it goes away with the app — [SafVaultStorage] is how a user keeps the
 * archive somewhere of their own.
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
 * A folder the user picked through `ACTION_OPEN_DOCUMENT_TREE`, held by a persisted permission.
 * zip4j cannot rewrite a document, so the archive is mirrored into `filesDir`: [refresh] pulls it
 * down before a read and [commit] pushes it back after a mutation. The mirror is keyed by the tree,
 * so two picked folders never share a working copy.
 */
class SafVaultStorage(private val context: Context, private val treeUri: Uri) : VaultStorage {

    private val resolver get() = context.contentResolver

    private fun mirrorDir(): File =
        File(File(context.filesDir, MIRROR_VAULT_ROOT), Integer.toHexString(treeUri.toString().hashCode()))

    private fun mirror(): File = File(mirrorDir(), VAULT_ARCHIVE_NAME)

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

    override fun localFile(): File = mirror().also { it.parentFile?.mkdirs() }

    /**
     * Copies the folder's archive down when the working copy is missing or a different length —
     * first open after a process restart, or a change made outside the app. A finished commit has
     * already left the two in step, so an unchanged vault costs one directory listing.
     */
    override fun refresh() {
        val remote = remoteArchive()
        val local = mirror()
        if (remote == null) {
            // The folder holds no vault: never serve a working copy of one that is no longer there.
            local.delete()
            return
        }
        val size = remoteSize(remote)
        if (local.exists() && size >= 0 && local.length() == size) return
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
        if (DocumentsContract.renameDocument(resolver, transfer, VAULT_ARCHIVE_NAME) == null) {
            throw IOException("Cannot finalize the vault archive")
        }
    }

    override fun exists(): Boolean = mirror().exists() || remoteArchive() != null

    override fun delete() {
        remoteArchive()?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
        runCatching { mirrorDir().deleteRecursively() }
    }

    private companion object {
        val ArchiveNames = listOf(VAULT_ARCHIVE_NAME, LEGACY_VAULT_ARCHIVE_NAME)
        val LookupNames = ArchiveNames + (VAULT_ARCHIVE_NAME + TRANSFER_SUFFIX)
    }
}

/** Picks the storage for the vault's current location, and knows the app-private copies to sweep. */
object VaultStores {

    /** The vault as it is configured right now: the picked folder if there is one, else app storage. */
    fun of(context: Context): VaultStorage =
        SafeStore.getTreeUri(context)?.let { SafVaultStorage(context, it) } ?: AppPrivateVaultStorage(context)

    /** Removes every app-private copy of the vault — the app-storage archive and any working copy. */
    fun purgeLocal(context: Context) {
        runCatching { File(context.filesDir, LOCAL_VAULT_ROOT).deleteRecursively() }
        runCatching { File(context.filesDir, MIRROR_VAULT_ROOT).deleteRecursively() }
    }
}
