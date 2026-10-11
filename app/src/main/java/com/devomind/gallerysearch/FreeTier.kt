package com.devomind.gallerysearch

/**
 * Where the free app stops and Pixa Pro begins.
 *
 * The numbers live in one file on purpose: a limit stated on five screens is five chances to disagree
 * about what the user paid for. Every predicate here takes `unlocked` and returns false when it is true,
 * so the whole object is a statement of the free tier rather than policy sprinkled through the features —
 * and it can be tested without a device, a Play account or a Context.
 *
 * Nothing here decides *how* a lock is shown. Features ask these questions and answer them with their own
 * affordance, so the accent picker can mark a row, the Safe can clamp a batch, and cleanup can put a
 * glyph on a tile without any of them inventing a new limit.
 */
object FreeTier {

    /**
     * The accents a free user may pick, counted from the front of [AccentPalette.choices]: Azure, Violet
     * and Teal stay theirs while Emerald, Amber, Rose and the dynamic system accent do not. The system
     * accent is locked like any other because it is the OS's palette rather than the app's — offering it
     * free would make the paid half of the list depend on which phone the user happens to own.
     */
    const val FreeAccents = 3

    /** Photos the Safe holds for free. Only moving a photo IN is charged — restoring is never blocked. */
    const val FreeSafePhotos = 50

    /** Smart albums a free user can create. Refreshing, renaming and re-prompting an existing one is not. */
    const val FreeSmartAlbums = 5

    /**
     * Cleanup categories that need the unlock: the two whose lists only exist because the scan did work
     * past metadata (bursts is the O(n²) grouping pass, sensitive runs the classifier). Duplicates,
     * similar, clutter, screenshots, the quality buckets and compression stay free.
     */
    val PaidCleanupCategories = setOf(
        CleanupAnalyzer.Category.BURSTS,
        CleanupAnalyzer.Category.NSFW
    )

    /** Photos one free compression run may take. Multi-photo compression is the paid workflow. */
    const val FreeCompressionPhotos = 1

    fun accentLocked(choice: AccentPalette.Choice, unlocked: Boolean): Boolean =
        !unlocked && AccentPalette.choices.indexOf(choice) >= FreeAccents

    /** Slots left in the Safe. [unlocked] gets the whole list it asked for, so a 500-photo move stays one batch. */
    fun safeRoom(inVault: Int, unlocked: Boolean): Int =
        if (unlocked) Int.MAX_VALUE else (FreeSafePhotos - inVault).coerceAtLeast(0)

    /** True when a compression pick is too large for the free app. */
    fun compressionBlocked(requested: Int, unlocked: Boolean): Boolean =
        !unlocked && requested > FreeCompressionPhotos

    /** True once a free user is at the album cap, so the create dialog should not open at all. */
    fun smartAlbumsBlocked(existing: Int, unlocked: Boolean): Boolean =
        !unlocked && existing >= FreeSmartAlbums

    fun cleanupLocked(category: CleanupAnalyzer.Category, unlocked: Boolean): Boolean =
        !unlocked && category in PaidCleanupCategories
}
