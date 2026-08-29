package com.devomind.gallerysearch

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

object DesignTokens {
    @ColorInt fun background(context: Context) = ContextCompat.getColor(context, R.color.metroBgPrimary)
    @ColorInt fun surface(context: Context) = ContextCompat.getColor(context, R.color.metroBgSecondary)
    @ColorInt fun card(context: Context) = ContextCompat.getColor(context, R.color.metroBgCard)
    @ColorInt fun accent(context: Context) = resolveColor(context, R.attr.accentColor, R.color.metroAccent)
    @ColorInt fun accentLight(context: Context) = resolveColor(context, R.attr.accentColorLight, R.color.metroAccentLight)
    @ColorInt fun accentMuted(context: Context) = resolveColor(context, R.attr.accentColor, R.color.metroAccent)

    private fun resolveColor(context: Context, attr: Int, fallbackRes: Int): Int {
        val ta = context.obtainStyledAttributes(intArrayOf(attr))
        return try {
            if (ta.hasValue(0)) ta.getColor(0, ContextCompat.getColor(context, fallbackRes))
            else ContextCompat.getColor(context, fallbackRes)
        } finally {
            ta.recycle()
        }
    }
    @ColorInt fun textPrimary(context: Context) = ContextCompat.getColor(context, R.color.metroTextPrimary)
    @ColorInt fun textSecondary(context: Context) = ContextCompat.getColor(context, R.color.metroTextSecondary)
    @ColorInt fun textDisabled(context: Context) = ContextCompat.getColor(context, R.color.metroTextDisabled)
    @ColorInt fun statusBar(context: Context) = ContextCompat.getColor(context, R.color.metroStatusBar)
    @ColorInt fun navBar(context: Context) = ContextCompat.getColor(context, R.color.metroNavBar)
    @ColorInt fun dimScrim(context: Context) = ContextCompat.getColor(context, R.color.metroDimScrim)

    const val SCREEN_TITLE_SIZE = 40f
    const val HEADER_TITLE_SIZE = 40f
    const val HEADER_SUBTITLE_SIZE = 13f
    const val BODY_TEXT = 16f
    const val CAPTION = 12f
    const val BUTTON_TEXT = 14f

    const val PADDING_SCREEN = 16
    const val PADDING_SECTION = 24
    const val GRID_GUTTER = 2

    const val HEADER_ALPHA = 0.35f
    const val SCROLLED_HEADER_ALPHA = 0.2f

    const val DISPLAY_CAP = 800
    const val GRID_SPAN_COUNT = 6
    const val GRID_MIN_COLUMNS = 2
    const val GRID_MAX_COLUMNS = 6
    const val GRID_DEFAULT_COLUMNS = 3
    const val GRID_THUMBNAIL_SPACING_DP = 2

    // Collage (justified rows) layout
    // Grid resolution for row widths. Set to ~1px granularity on typical phones so each tile's
    // rendered width matches its aspect ratio within a pixel — this is what lets the mosaic show
    // every photo uncropped with no letterbox "container".
    const val COLLAGE_SPAN_COUNT = 1080
    // Baseline images-per-row; smaller => taller rows / bigger thumbnails. This is the default
    // (COLLAGE_SCALE_DEFAULT) baseline — the actual value is resolved per user scale via
    // [collageRowsPerWidth]. Anchored at level 3; default scale is 4 (~3 thumbnails per row).
    const val COLLAGE_TARGET_ROWS_PER_WIDTH = 2.3f

    // User-adjustable collage thumbnail scale — a discrete level (like grid columns). Higher level =
    // smaller thumbnails (more images per row); lower level = bigger thumbnails. Adjustable by pinch
    // gesture and in Settings, persisted via IndexPreferences.
    const val COLLAGE_SCALE_MIN = 1
    const val COLLAGE_SCALE_MAX = 5
    const val COLLAGE_SCALE_DEFAULT = 4
    // Level (1..5) → images-per-row baseline. Level 3 is the anchor; level 4 is the default (~3 thumbnails/row).
    private val COLLAGE_SCALE_ROWS = floatArrayOf(1.4f, 1.8f, 2.3f, 2.8f, 3.3f)

    /** Resolves a collage scale level (1..5) to its images-per-row baseline. */
    fun collageRowsPerWidth(scaleLevel: Int): Float =
        COLLAGE_SCALE_ROWS[scaleLevel.coerceIn(COLLAGE_SCALE_MIN, COLLAGE_SCALE_MAX) - 1]
    // Aves-style aspect clamping: prevent extreme tall/wide panoramas from dominating a row while
    // still preserving each photo's true shape (no square-crop distortion).
    const val COLLAGE_MIN_ASPECT = 9f / 32f
    const val COLLAGE_MAX_ASPECT = 32f / 9f
    // A sparse final row whose natural height would exceed this multiple of the target is capped
    // instead of ballooning (Aves heightMaxFactor).
    const val COLLAGE_MAX_INCOMPLETE_ROW_HEIGHT_RATIO = 2.4f
    // Uniform gap around each mosaic tile (dp). The row math subtracts this from the available
    // width and folds it back into each span, so tiles stay aspect-exact with visible spacing.
    const val COLLAGE_TILE_PADDING_DP = 2

    const val MENU_FADE_DURATION_MS: Long = 160L
    const val MENU_NEAR_FADE_DURATION_MS: Long = 120L
    const val MENU_NEAR_X_DP: Float = 96f
    const val MENU_NEAR_Y_DP: Float = 116f
    const val SCROLL_THRESHOLD_PX: Int = 32
    const val SEARCH_METADATA_HARD_CAP: Int = 80
    const val SEARCH_INPUT_DEBOUNCE_MS: Long = 180L
    const val INDEX_BACKOFF_SECONDS: Long = 10L
    const val INDEX_LIVE_REFRESH_STEP: Int = 20
}
