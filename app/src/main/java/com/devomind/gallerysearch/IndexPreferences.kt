package com.devomind.gallerysearch

import android.content.Context

object IndexPreferences {
    private const val PrefName = "index_prefs"
    private const val KeyLastIndexed = "last_indexed_time"
    private const val KeyOptimalThreads = "optimal_thread_count"
    private const val KeyShowPinnedCollections = "show_pinned_collections"
    private const val KeyGridColumnCount = "grid_column_count"
    private const val KeyCollageScale = "collage_scale_level"
    private const val KeyCollageLayout = "use_collage_layout"
    private const val KeyIndexPaused = "index_paused"
    private const val KeyIndexStopped = "index_stopped"
    private const val KeyCleanupPaused = "cleanup_paused"
    private const val KeyIndexConsentGiven = "index_consent_given"
    private const val KeyIndexConsentAsked = "index_consent_asked"
    private const val KeyChargingOnly = "index_charging_only"
    private const val KeySmartAlbumOnboardingDismissed = "smart_album_onboarding_dismissed"
    private const val KeyIndexProgressPercent = "index_progress_percent"
    private const val KeyBlurSensitive = "blur_sensitive_content"

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
}
