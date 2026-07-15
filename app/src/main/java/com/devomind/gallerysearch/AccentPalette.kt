package com.devomind.gallerysearch

import android.app.Activity
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes

/**
 * The user-selectable accent colors for the AMOLED Metro theme. Each [Choice] maps to a theme
 * overlay style ([overlayStyle]) that sets the `accentColor` family + `colorAccent`, and to a
 * concrete [solid] / [light] @ColorInt used where a theme attribute can't reach (e.g. Canvas paints).
 *
 * Applied per-activity via [apply] in each `onCreate` (before `super.onCreate` so the overlay is in
 * place before the content view inflates). Stored selection is read from [IndexPreferences].
 */
object AccentPalette {

    enum class Choice(
        val key: String,
        val displayName: String,
        @StyleRes val overlayStyle: Int,
        @ColorInt val solid: Int,
        @ColorInt val light: Int
    ) {
        AZURE("azure", "Azure", R.style.Accent_Azure, 0xFF3B9EFF.toInt(), 0xFF6DB8FF.toInt()),
        VIOLET("violet", "Violet", R.style.Accent_Violet, 0xFF8B5CF6.toInt(), 0xFFA78BFA.toInt()),
        TEAL("teal", "Teal", R.style.Accent_Teal, 0xFF14B8A6.toInt(), 0xFF2DD4BF.toInt()),
        EMERALD("emerald", "Emerald", R.style.Accent_Emerald, 0xFF22C55E.toInt(), 0xFF4ADE80.toInt()),
        AMBER("amber", "Amber", R.style.Accent_Amber, 0xFFF59E0B.toInt(), 0xFFFBBF24.toInt()),
        ROSE("rose", "Rose", R.style.Accent_Rose, 0xFFF43F5E.toInt(), 0xFFFB7185.toInt());

        companion object {
            fun fromKey(key: String?): Choice = entries.firstOrNull { it.key == key } ?: AZURE
        }
    }

    val choices: List<Choice> get() = Choice.entries.toList()

    /** The currently selected accent. */
    fun current(activity: Activity): Choice =
        Choice.fromKey(IndexPreferences.getAccentColor(activity))

    /**
     * Applies the user's chosen accent overlay to [activity]. Must be called in `onCreate` before
     * `super.onCreate(...)` so views inflate with the resolved accent.
     */
    fun apply(activity: Activity) {
        apply(activity, current(activity))
    }

    /** Applies a specific [choice] overlay to [activity]. */
    fun apply(activity: Activity, choice: Choice) {
        activity.theme.applyStyle(choice.overlayStyle, true)
    }

    /** Convenience @ColorInt for the current accent's solid color. */
    @ColorInt fun solid(activity: Activity): Int = current(activity).solid

    /** Convenience @ColorInt for the current accent's light variant. */
    @ColorInt fun light(activity: Activity): Int = current(activity).light
}
