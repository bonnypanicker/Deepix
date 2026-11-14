package com.devomind.gallerysearch

/**
 * Storage rules shared by the photo editor's save actions.
 *
 * Copies should remain beside their source whenever MediaStore permits it. A selected-photo grant
 * can reject that destination, so the write layer makes one safe fallback to the app-owned
 * collection only after the sibling insertion is refused.
 */
internal object EditorSavePolicy {

    fun copyDestinationFolder(sourceFolder: String?): String? = sourceFolder

    fun shouldRetryCopyInOwnedFolder(
        sdkInt: Int,
        destinationFolder: String?,
        failure: Exception
    ): Boolean = sdkInt >= 29 && destinationFolder != null &&
        (failure is SecurityException || failure is IllegalArgumentException)

    /**
     * The volume an edited copy belongs on: the source's own, so a video kept on a card is written
     * back to the card beside its original instead of to internal storage at the same relative path.
     *
     * Null means the caller's default, which is the primary volume — the right answer when the
     * volume cannot be named, and the only one before Q, where the DATA path already names the
     * volume. A null [sourceFolder] also yields null: a copy that is not going beside its original
     * belongs in the app's own folder, and that folder lives on primary storage.
     */
    fun copyVolume(sourceFolder: String?, sourceVolume: String?): String? =
        sourceVolume?.takeIf { it.isNotBlank() && !sourceFolder.isNullOrBlank() }

    /** Android 11+ requires an explicit MediaStore grant before rewriting another app's image. */
    fun requiresWriteConsent(sdkInt: Int, scheme: String?, authority: String?): Boolean =
        sdkInt >= 30 && scheme == "content" && authority == "media"
}
