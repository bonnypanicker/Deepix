package com.devomind.gallerysearch

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

enum class BottomBarDestination(
    val key: String,
    @StringRes val labelRes: Int,
    @DrawableRes val iconRes: Int
) {
    Collection("collection", R.string.tab_collection, R.drawable.ic_deepix_collections_24_regular),
    Videos("videos", R.string.tab_videos, R.drawable.ic_deepix_video_24_regular),
    Albums("albums", R.string.tab_albums, R.drawable.ic_deepix_albums_24_regular),
    Favorites("favorites", R.string.tab_favorites, R.drawable.ic_deepix_favorite_24_regular),
    Folders("folders", R.string.tab_folders, R.drawable.ic_deepix_folders_24_regular),
    Safe("safe", R.string.safe_title, R.drawable.ic_deepix_safe_nav_24_regular);

    companion object {
        fun fromKey(key: String?): BottomBarDestination? = entries.firstOrNull { it.key == key }
    }
}

object BottomBarConfig {
    private const val PrefName = "bottom_bar_config"
    private const val KeyOrder = "order"
    private const val KeyFoldersEnabled = "folders_enabled"
    private const val KeySafeEnabled = "safe_enabled"
    private const val KeyDefaultPage = "default_page"
    private const val Separator = ","

    private val defaultOrder = listOf(
        BottomBarDestination.Collection,
        BottomBarDestination.Videos,
        BottomBarDestination.Albums,
        BottomBarDestination.Favorites,
        BottomBarDestination.Folders,
        BottomBarDestination.Safe
    )

    fun order(context: Context): List<BottomBarDestination> {
        val stored = prefs(context).getString(KeyOrder, null)
            ?.split(Separator)
            .orEmpty()
            .mapNotNull(BottomBarDestination::fromKey)
            .distinct()
        return stored + defaultOrder.filterNot { it in stored }
    }

    fun setOrder(context: Context, destinations: List<BottomBarDestination>) {
        val complete = destinations.distinct() + defaultOrder.filterNot { it in destinations }
        prefs(context).edit().putString(KeyOrder, complete.joinToString(Separator) { it.key }).apply()
    }

    fun isFoldersEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KeyFoldersEnabled, false)

    fun setFoldersEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KeyFoldersEnabled, enabled).apply()
        repairDefault(context)
    }

    fun isSafeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KeySafeEnabled, false)

    fun setSafeEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KeySafeEnabled, enabled).apply()
        repairDefault(context)
    }

    fun enabledOrder(context: Context): List<BottomBarDestination> = order(context).filter {
        when (it) {
            BottomBarDestination.Folders -> isFoldersEnabled(context)
            BottomBarDestination.Safe -> isSafeEnabled(context)
            else -> true
        }
    }

    fun defaultPage(context: Context): BottomBarDestination {
        val configured = BottomBarDestination.fromKey(prefs(context).getString(KeyDefaultPage, null))
        return configured?.takeIf { it in enabledOrder(context) }
            ?: BottomBarDestination.Collection
    }

    fun setDefaultPage(context: Context, destination: BottomBarDestination) {
        val valid = destination.takeIf { it in enabledOrder(context) } ?: BottomBarDestination.Collection
        prefs(context).edit().putString(KeyDefaultPage, valid.key).apply()
    }

    private fun repairDefault(context: Context) {
        val stored = BottomBarDestination.fromKey(prefs(context).getString(KeyDefaultPage, null))
        if (stored != null && stored !in enabledOrder(context)) {
            setDefaultPage(context, BottomBarDestination.Collection)
        }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
}
