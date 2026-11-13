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

    /** Android 11+ requires an explicit MediaStore grant before rewriting another app's image. */
    fun requiresWriteConsent(sdkInt: Int, scheme: String?, authority: String?): Boolean =
        sdkInt >= 30 && scheme == "content" && authority == "media"
}
