package com.devomind.gallerysearch

import android.content.Context
import android.content.SharedPreferences
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
    private const val KeyEnabled = "enabled"
    private const val KeyDefaultPage = "default_page"
    // Superseded by KeyEnabled; read once to migrate a user who picked the optional tabs earlier.
    private const val KeyAlbumsEnabled = "albums_enabled"
    private const val KeyFoldersEnabled = "folders_enabled"
    private const val KeySafeEnabled = "safe_enabled"
    private const val Separator = ","

    /** The bottom bar never drops below this many destinations. */
    const val MinEnabled = 3

    private val defaultOrder = listOf(
        BottomBarDestination.Collection,
        BottomBarDestination.Albums,
        BottomBarDestination.Videos,
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

    fun isEnabled(context: Context, destination: BottomBarDestination): Boolean =
        destination in enabledKeys(context)

    /**
     * Shows or hides a destination. Refuses — returning false and changing nothing — when hiding it
     * would take the bar below [MinEnabled], so the bar always keeps enough pages to navigate.
     */
    fun setEnabled(context: Context, destination: BottomBarDestination, enabled: Boolean): Boolean {
        val keys = enabledKeys(context).toMutableSet()
        if (enabled) {
            if (!keys.add(destination)) return true
        } else {
            if (destination !in keys) return true
            if (keys.size <= MinEnabled) return false
            keys.remove(destination)
        }
        saveEnabled(context, keys)
        repairDefault(context)
        return true
    }

    fun enabledOrder(context: Context): List<BottomBarDestination> {
        val enabled = enabledKeys(context)
        return order(context).filter { it in enabled }
    }

    fun defaultPage(context: Context): BottomBarDestination {
        val enabled = enabledOrder(context)
        val configured = BottomBarDestination.fromKey(prefs(context).getString(KeyDefaultPage, null))
        return configured?.takeIf { it in enabled } ?: enabled.firstOrNull() ?: BottomBarDestination.Collection
    }

    fun setDefaultPage(context: Context, destination: BottomBarDestination) {
        val enabled = enabledOrder(context)
        val valid = destination.takeIf { it in enabled } ?: enabled.firstOrNull() ?: BottomBarDestination.Collection
        prefs(context).edit().putString(KeyDefaultPage, valid.key).apply()
    }

    private fun repairDefault(context: Context) {
        val stored = BottomBarDestination.fromKey(prefs(context).getString(KeyDefaultPage, null))
        if (stored != null && stored !in enabledOrder(context)) {
            setDefaultPage(context, enabledOrder(context).firstOrNull() ?: BottomBarDestination.Collection)
        }
    }

    private fun enabledKeys(context: Context): Set<BottomBarDestination> {
        val prefs = prefs(context)
        val stored = prefs.getString(KeyEnabled, null) ?: return migratedEnabled(prefs)
        return stored.split(Separator).mapNotNull(BottomBarDestination::fromKey).toSet()
    }

    private fun saveEnabled(context: Context, keys: Set<BottomBarDestination>) {
        prefs(context).edit().putString(KeyEnabled, keys.joinToString(Separator) { it.key }).apply()
    }

    /** Rebuilds the enabled set from the per-tab flags that [KeyEnabled] replaced. */
    private fun migratedEnabled(prefs: SharedPreferences): Set<BottomBarDestination> {
        val keys = linkedSetOf(
            BottomBarDestination.Collection,
            BottomBarDestination.Videos,
            BottomBarDestination.Favorites
        )
        if (prefs.getBoolean(KeyAlbumsEnabled, true)) keys.add(BottomBarDestination.Albums)
        if (prefs.getBoolean(KeyFoldersEnabled, false)) keys.add(BottomBarDestination.Folders)
        if (prefs.getBoolean(KeySafeEnabled, false)) keys.add(BottomBarDestination.Safe)
        return keys
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PrefName, Context.MODE_PRIVATE)
}
