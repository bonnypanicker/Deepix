package com.devomind.gallerysearch

import android.content.Context

object IndexPreferences {
    private const val PrefName = "index_prefs"
    private const val KeyLastIndexed = "last_indexed_time"
    private const val KeyOptimalThreads = "optimal_thread_count"
    /** Legacy key: held the OOM batch size as a short-circuit before it became a clamp. */
    private const val KeyIndexBatchSizeOverride = "index_batch_size_override"
    private const val KeyOomBatchCap = "index_oom_batch_cap"
    private const val KeyShowPinnedCollections = "show_pinned_collections"
    private const val KeyGridColumnCount = "grid_column_count"
    private const val KeyCollageScale = "collage_scale_level"
    private const val KeyCollageLayout = "use_collage_layout"
    private const val KeyShowAlbumFolderSize = "show_album_folder_size"
    private const val KeyIndexPaused = "index_paused"
    private const val KeyIndexStopped = "index_stopped"
    private const val KeyCleanupPaused = "cleanup_paused"
    private const val KeyIndexConsentGiven = "index_consent_given"
    private const val KeyChargingOnly = "index_charging_only"
    private const val KeyNightChargingOnly = "index_night_charging_only"
    private const val KeyRequiresUserIdle = "index_requires_user_idle"
    private const val KeyRequireBatteryNotLow = "index_require_battery_not_low"
    private const val KeyRequireStorageNotLow = "index_require_storage_not_low"
    private const val KeyLastWaitReason = "index_last_wait_reason"
    private const val KeySmartAlbumOnboardingDismissed = "smart_album_onboarding_dismissed"
    private const val KeyIndexProgressPercent = "index_progress_percent"
    private const val KeyBlurSensitive = "blur_sensitive_content"
    private const val KeyRecycleBinEnabled = "recycle_bin_enabled"
    private const val KeySkipDeleteConfirm = "skip_delete_confirm"
    private const val KeySafeStorageRoot = "safe_storage_root"
    private const val KeyAccentColor = "accent_color"
    /** Set once the user takes the FirstRun onboarding path. */
    private const val KeyFirstRunDone = "first_run_done"

    /** Public directory the encrypted Safe zip lives under. */
    const val SAFE_ROOT_PICTURES = "pictures"
    const val SAFE_ROOT_DOCUMENTS = "documents"

    fun hasSeenFirstRun(context: Context): Boolean =
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE).getBoolean(KeyFirstRunDone, false)

    fun setFirstRunDone(context: Context, done: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyFirstRunDone, done)
            .apply()
    }

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

    /** Whether indexing was ever started. Indexing needs no approval dialog to begin. */
    fun isIndexConsentGiven(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyIndexConsentGiven, false)
    }

    fun setIndexConsentGiven(context: Context, given: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyIndexConsentGiven, given)
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

    /** Optional future gate: require screen-off/device-idle before the heavy CLIP pass runs. */
    fun isUserIdleRequiredForIndexing(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyRequiresUserIdle, false)
    }

    fun setUserIdleRequiredForIndexing(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyRequiresUserIdle, enabled)
            .apply()
    }

    /** Aggressive default: allow battery drain unless the user later opts into this gate. */
    fun isBatteryNotLowRequiredForIndexing(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyRequireBatteryNotLow, false)
    }

    fun setBatteryNotLowRequiredForIndexing(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyRequireBatteryNotLow, enabled)
            .apply()
    }

    /** Storage-low is a hard safety gate: index writes need room to complete atomically. */
    fun isStorageNotLowRequiredForIndexing(context: Context): Boolean {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getBoolean(KeyRequireStorageNotLow, true)
    }

    fun setStorageNotLowRequiredForIndexing(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KeyRequireStorageNotLow, enabled)
            .apply()
    }

    fun getLastIndexWaitReason(context: Context): IndexWaitReason? {
        val raw = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getString(KeyLastWaitReason, null)
        return raw?.let { value ->
            runCatching { IndexWaitReason.valueOf(value) }.getOrNull()
        }
    }

    fun setLastIndexWaitReason(context: Context, reason: IndexWaitReason?) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .apply {
                if (reason == null) remove(KeyLastWaitReason)
                else putString(KeyLastWaitReason, reason.name)
            }
            .apply()
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

    /** Returns 0 if no thread count has been stored yet. */
    fun getOptimalThreadCount(context: Context): Int {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getInt(KeyOptimalThreads, 0)
    }

    /**
     * The largest batch indexing may attempt after an earlier [OutOfMemoryError], or 0 when nothing
     * has been recorded. This is a clamp, not a replacement: the device tier and the thermal profile
     * still choose the size, and this only lowers it. Builds before that treated the value as a
     * short-circuit left the legacy key behind, which pinned every profile to the same number for
     * the rest of the install, so it is adopted once here instead of dropped.
     */
    fun getOomBatchCap(context: Context): Int {
        val prefs = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        val stored = prefs.getInt(KeyOomBatchCap, 0)
        if (stored > 0) return stored
        val legacy = prefs.getInt(KeyIndexBatchSizeOverride, 0)
        if (legacy > 0) {
            prefs.edit()
                .remove(KeyIndexBatchSizeOverride)
                .putInt(KeyOomBatchCap, legacy)
                .apply()
        }
        return legacy
    }

    /** Pin the cap low after an OOM. Passes that finish clean climb back via [healOomBatchCap]. */
    fun saveOomBatchCap(context: Context, size: Int) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putInt(KeyOomBatchCap, size.coerceAtLeast(1))
            .remove(KeyIndexBatchSizeOverride)
            .apply()
    }

    /**
     * One step back toward the device ceiling after a pass that encoded without an OOM. Recovery is
     * deliberately gradual — resetting straight to full size re-runs the batch that just failed.
     */
    fun healOomBatchCap(context: Context, ceiling: Int) {
        val prefs = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
        val current = getOomBatchCap(context)
        if (current <= 0) return
        if (current + 1 >= ceiling) {
            prefs.edit().remove(KeyOomBatchCap).apply()
        } else {
            prefs.edit().putInt(KeyOomBatchCap, current + 1).apply()
        }
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

    private const val KeySafeVaultDir = "safe_vault_dir"

    /**
     * Custom vault directory (absolute path) chosen via the Settings folder picker. Null/blank
     * means "no custom folder" — [getSafeStorageRoot] (Pictures/Documents) applies instead.
     */
    fun getSafeVaultDir(context: Context): String? {
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getString(KeySafeVaultDir, null)?.takeIf { it.isNotBlank() }
    }

    fun setSafeVaultDir(context: Context, path: String?) {
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(KeySafeVaultDir, path ?: "")
            .apply()
    }

    /** Process-wide cache of the accent selection. MainActivity reads this before super.onCreate()
     *  — StrictMode measured a ~400 ms main-thread block on cold start when it hit disk instead,
     *  so GallerySearchApp warms it on a background thread the moment the process starts. */
    @Volatile
    private var cachedAccentKey: String? = null

    /** Loads the accent selection into [cachedAccentKey]; called by the startup pre-warm thread. */
    fun warmAccentCache(context: Context) {
        if (cachedAccentKey == null) {
            cachedAccentKey = context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
                .getString(KeyAccentColor, null) ?: "azure"
        }
    }

    /** The selected accent color key (see [AccentPalette.Choice]); null/missing = Azure default. */
    fun getAccentColor(context: Context): String {
        cachedAccentKey?.let { return it }
        return context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .getString(KeyAccentColor, null)?.also { cachedAccentKey = it } ?: "azure"
    }

    fun setAccentColor(context: Context, key: String) {
        cachedAccentKey = key
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
            .edit()
            .putString(KeyAccentColor, key)
            .apply()
    }
}
