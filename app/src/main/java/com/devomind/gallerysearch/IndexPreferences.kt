package com.devomind.gallerysearch

import android.content.Context

object IndexPreferences {
    private const val PrefName = "index_prefs"
    private const val KeyLastIndexed = "last_indexed_time"
    private const val KeyOptimalThreads = "optimal_thread_count"
    private const val KeyIndexBatchSizeOverride = "index_batch_size_override"
    private const val KeyShowPinnedCollections = "show_pinned_collections"
    private const val KeyGridColumnCount = "grid_column_count"
    private const val KeyCollageScale = "collage_scale_level"
    private const val KeyCollageLayout = "use_collage_layout"
    private const val KeyShowAlbumFolderSize = "show_album_folder_size"
    private const val KeyIndexPaused = "index_paused"
    private const val KeyIndexStopped = "index_stopped"
    private const val KeyCleanupPaused = "cleanup_paused"
    private const val KeyIndexConsentGiven = "index_consent_given"
    private const val KeyIndexConsentAsked = "index_consent_asked"
    private const val KeyChargingOnly = "index_charging_only"
    private const val KeyNightChargingOnly = "index_night_charging_only"
    private const val KeySmartAlbumOnboardingDismissed = "smart_album_onboarding_dismissed"
    private const val KeyIndexProgressPercent = "index_progress_percent"
    private const val KeyBlurSensitive = "blur_sensitive_content"
    private const val KeyRecycleBinEnabled = "recycle_bin_enabled"
    private const val KeySkipDeleteConfirm = "skip_delete_confirm"
    private const val KeySafeStorageRoot = "safe_storage_root"
    private const val KeyAccentColor = "accent_color"

    /** Public directory the encrypted Safe zip lives under. */
    const val SAFE_ROOT_PICTURES = "pictures"
    const val SAFE_ROOT_DOCUMENTS = "documents"

    fun saveLastIndexedTime(context: Context) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putLong(KeyLastIndexed, System.currentTimeMillis())
            .apply()
    }

    fun getLastIndexedTime(context: Context): Long {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getLong(KeyLastIndexed, 0L)
    }

    fun loadLastIndexedTime(context: Context): Long = getLastIndexedTime(context)

    /** Last known indexing progress (0..100), so the Settings screen can show it while paused/idle. */
    fun getIndexProgressPercent(context: Context): Int {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getInt(KeyIndexProgressPercent, 0)
    }

    fun setIndexProgressPercent(context: Context, percent: Int) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putInt(KeyIndexProgressPercent, percent.coerceIn(0, 100))
            .apply()
    }

    fun isIndexPaused(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyIndexPaused, false)
    }

    fun setIndexPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyIndexPaused, paused)
            .apply()
    }

    /** User explicitly stopped indexing: don't auto-restart on browse/relaunch, no notification. */
    fun isIndexStopped(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyIndexStopped, false)
    }

    fun setIndexStopped(context: Context, stopped: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyIndexStopped, stopped)
            .apply()
    }

    fun isCleanupPaused(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyCleanupPaused, false)
    }

    fun setCleanupPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyCleanupPaused, paused)
            .apply()
    }

    /** When on, deletes go to the 30-day Recycle Bin instead of being removed immediately. */
    fun isRecycleBinEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyRecycleBinEnabled, true)
    }

    fun setRecycleBinEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyRecycleBinEnabled, enabled)
            .apply()
    }

    /**
     * When on (and All-files access is granted), skip the app's own confirmation dialog and delete
     * directly. Independent of the Recycle Bin toggle — "direct" refers to the confirmation, the
     * destination (bin vs permanent) is [isRecycleBinEnabled].
     */
    fun isSkipDeleteConfirm(context: Context): Boolean = true

    fun setSkipDeleteConfirm(context: Context, skip: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeySkipDeleteConfirm, true)
            .apply()
    }

    /** Whether the user has approved building the AI photo index. */
    fun isIndexConsentGiven(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyIndexConsentGiven, false)
    }

    fun setIndexConsentGiven(context: Context, given: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyIndexConsentGiven, given)
            .putBoolean(KeyIndexConsentAsked, true)
            .apply()
    }

    /** Whether we've shown the one-time consent prompt (so we don't auto-nag again). */
    fun wasIndexConsentAsked(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyIndexConsentAsked, false)
    }

    fun setIndexConsentAsked(context: Context) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyIndexConsentAsked, true)
            .apply()
    }

    /** Limit background indexing to while the device is charging. */
    fun isChargingOnlyIndexing(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyChargingOnly, false)
    }

    fun setChargingOnlyIndexing(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyChargingOnly, enabled)
            .apply()
    }

    /**
     * Sub-option of "index only while charging": further restrict indexing to night-time hours
     * (roughly 22:00–07:00 device local time) so the heavy scan runs when the phone is least used.
     * Only meaningful when [isChargingOnlyIndexing] is also on.
     */
    fun isNightChargingOnly(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyNightChargingOnly, false)
    }

    fun setNightChargingOnly(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyNightChargingOnly, enabled)
            .apply()
    }

    /**
     * Night charging window in device-local 24h hour-of-day: 22:00 (inclusive) through 07:00
     * (exclusive). Used by [IndexWorker] when [isNightChargingOnly] is on so the heavy scan runs
     * overnight instead of as soon as the phone is plugged in.
     */
    const val NIGHT_CHARGE_START_HOUR = 22
    const val NIGHT_CHARGE_END_HOUR = 7

    /** True if the given hour-of-day (0..23) falls inside the night charging window. */
    fun isNightChargeHour(hourOfDay: Int): Boolean {
        return hourOfDay >= NIGHT_CHARGE_START_HOUR || hourOfDay < NIGHT_CHARGE_END_HOUR
    }

    /** Whether the user dismissed the "Create a smart album" onboarding card. */
    fun isSmartAlbumOnboardingDismissed(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeySmartAlbumOnboardingDismissed, false)
    }

    fun setSmartAlbumOnboardingDismissed(context: Context) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeySmartAlbumOnboardingDismissed, true)
            .apply()
    }

    // ---- One-time gesture hints (each shown once, then never again) ----

    /** True if the one-shot hint identified by [key] has already been shown. */
    fun wasHintShown(context: Context, key: String): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean("hint_$key", false)
    }

    fun setHintShown(context: Context, key: String) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean("hint_$key", true)
            .apply()
    }

    const val HINT_PINCH_GRID = "pinch_grid"
    const val HINT_LONG_PRESS_SELECT = "long_press_select"
    const val HINT_VIEWER_DISMISS = "viewer_dismiss"

    fun saveOptimalThreadCount(context: Context, count: Int) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putInt(KeyOptimalThreads, count)
            .apply()
    }

    /** Returns 0 if no benchmark has been run yet. */
    fun getOptimalThreadCount(context: Context): Int {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getInt(KeyOptimalThreads, 0)
    }

    /** Returns the OOM-persisted batch size override, or 0 if none has been saved. */
    fun getIndexBatchSizeOverride(context: Context): Int {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getInt(KeyIndexBatchSizeOverride, 0)
    }

    /** Persist a reduced batch size after an OOM so the next indexing run uses it. */
    fun saveIndexBatchSizeOverride(context: Context, size: Int) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putInt(KeyIndexBatchSizeOverride, size)
            // The cached thread count was tuned for the old (larger) batch shape — invalidate it
            // so the next run re-benchmarks against the new batch size instead of running with a
            // stale, mismatched thread count.
            .putInt(KeyOptimalThreads, 0)
            .apply()
    }

    fun isShowPinnedInCollections(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyShowPinnedCollections, true)
    }

    fun setShowPinnedInCollections(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyShowPinnedCollections, enabled)
            .apply()
    }

    fun getGridColumnCount(context: Context): Int {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getInt(KeyGridColumnCount, DesignTokens.GRID_DEFAULT_COLUMNS)
    }

    fun setGridColumnCount(context: Context, count: Int) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putInt(KeyGridColumnCount, count)
            .apply()
    }

    /** Collage thumbnail scale level (1..5); higher = smaller thumbnails. See [DesignTokens.collageRowsPerWidth]. */
    fun getCollageScale(context: Context): Int {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getInt(KeyCollageScale, DesignTokens.COLLAGE_SCALE_DEFAULT)
    }

    fun setCollageScale(context: Context, level: Int) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putInt(KeyCollageScale, level.coerceIn(DesignTokens.COLLAGE_SCALE_MIN, DesignTokens.COLLAGE_SCALE_MAX))
            .apply()
    }

    /** Beta: blur photos flagged as sensitive/NSFW by on-device AI; tap to reveal. */
    fun isBlurSensitive(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyBlurSensitive, false)
    }

    fun setBlurSensitive(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyBlurSensitive, enabled)
            .apply()
    }

    fun isCollageLayout(context: Context): Boolean {
        // Default to false to use the new grid mode by default
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyCollageLayout, false)
    }

    fun setCollageLayout(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyCollageLayout, enabled)
            .apply()
    }

    /** Albums page option: append each real device album's total storage size to its subtitle. */
    fun isShowAlbumFolderSize(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyShowAlbumFolderSize, false)
    }

    fun setShowAlbumFolderSize(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyShowAlbumFolderSize, enabled)
            .apply()
    }

    /** Which public folder the Safe vault is stored under (see [SAFE_ROOT_*]). */
    fun getSafeStorageRoot(context: Context): String {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getString(KeySafeStorageRoot, SAFE_ROOT_PICTURES)!!
    }

    fun setSafeStorageRoot(context: Context, root: String) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(KeySafeStorageRoot, root)
            .apply()
    }

    /** The selected accent color key (see [AccentPalette.Choice]); null/missing = Azure default. */
    fun getAccentColor(context: Context): String {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getString(KeyAccentColor, null) ?: "azure"
    }

    fun setAccentColor(context: Context, key: String) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(KeyAccentColor, key)
            .apply()
    }
}
