package com.devomind.gallerysearch

import android.content.Context
import androidx.annotation.ColorInt
import androidx.core.content.ContextCompat

object DesignTokens {
    @ColorInt fun background(context: Context) = ContextCompat.getColor(context, R.color.metroBgPrimary)
    @ColorInt fun surface(context: Context) = ContextCompat.getColor(context, R.color.metroBgSecondary)
    @ColorInt fun card(context: Context) = ContextCompat.getColor(context, R.color.metroBgCard)
    @ColorInt fun accent(context: Context) = ContextCompat.getColor(context, R.color.metroAccent)
    @ColorInt fun accentLight(context: Context) = ContextCompat.getColor(context, R.color.metroAccentLight)
    @ColorInt fun accentMuted(context: Context) = ContextCompat.getColor(context, R.color.metroAccentMuted)
    @ColorInt fun textPrimary(context: Context) = ContextCompat.getColor(context, R.color.metroTextPrimary)
    @ColorInt fun textSecondary(context: Context) = ContextCompat.getColor(context, R.color.metroTextSecondary)
    @ColorInt fun textDisabled(context: Context) = ContextCompat.getColor(context, R.color.metroTextDisabled)
    @ColorInt fun statusBar(context: Context) = ContextCompat.getColor(context, R.color.metroStatusBar)
    @ColorInt fun navBar(context: Context) = ContextCompat.getColor(context, R.color.metroNavBar)
    @ColorInt fun selectionBg(context: Context) = ContextCompat.getColor(context, R.color.metroSelectionBackground)
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

    const val MENU_FADE_DURATION_MS: Long = 160L
    const val MENU_NEAR_FADE_DURATION_MS: Long = 120L
    const val MENU_NEAR_X_DP: Float = 96f
    const val MENU_NEAR_Y_DP: Float = 116f
    const val SCROLL_THRESHOLD_PX: Int = 32
    const val SEARCH_METADATA_HARD_CAP: Int = 80
    const val INDEX_BACKOFF_SECONDS: Long = 10L
    const val INDEX_LIVE_REFRESH_STEP: Int = 20
}
