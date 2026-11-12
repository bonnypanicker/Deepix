package com.devomind.gallerysearch

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Looks for a Safe file this install did not create.
 *
 * After a reinstall the app cannot list the previous install's MediaStore rows, so this probe is
 * deliberately weaker than [SafeManager.archiveExists]: it reports only that a file with a vault's
 * name is where a vault usually lives, never that the app can read it. Nothing here opens or
 * verifies the file — the restore flow checks the password against whatever the user ends up
 * pointing the picker at, and a wrong file is simply not adopted.
 */
object VaultDetector {

    /** The folder new vaults are created in. */
    private const val VAULT_FOLDER = "PixaSafe"

    /** The provider that owns `Documents/`; its document ids are `primary:<path>`. */
    private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

    private val CandidateNames = listOf(VAULT_ARCHIVE_NAME, LEGACY_VAULT_ARCHIVE_NAME)

    /** The folders a vault has ever been created in, newest first. */
    private fun candidateDirs(): List<File> {
        val documents = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
        return listOf(File(documents, VAULT_FOLDER), documents)
    }

    /**
     * True when a file named like a vault is visible to [File] where the app would put one. A false
     * answer only means nothing was found by name — the file may still exist and simply not be
     * listable, which is exactly what the restore picker is for.
     */
    fun findExistingVault(): Boolean = candidateDirs().any { dir ->
        CandidateNames.any { name -> runCatching { File(dir, name).exists() }.getOrDefault(false) }
    }

    /**
     * Where the restore picker should open. This is a hint, not a lookup: a document uri for the
     * external-storage provider needs no permission and the picker falls back to its own location if
     * it cannot resolve one, so a wrong guess costs nothing.
     */
    val initialUri: Uri
        get() = DocumentsContract.buildDocumentUri(
            EXTERNAL_STORAGE_AUTHORITY,
            "primary:Documents/$VAULT_FOLDER/$VAULT_ARCHIVE_NAME"
        )
}
