package com.devomind.gallerysearch

import android.app.Activity
import android.content.Context
import android.os.Build
import androidx.annotation.ColorInt
import androidx.annotation.StyleRes
import androidx.core.content.ContextCompat

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
        ROSE("rose", "Rose", R.style.Accent_Rose, 0xFFF43F5E.toInt(), 0xFFFB7185.toInt()),

        /**
         * The device's own accent, resolved by the framework rather than by this app (see
         * `values-v31/themes.xml`). The literals here are the fallback the picker's dot and the
         * attribute lookup use when the OS hands back nothing usable — they are Azure, the app default,
         * so a failed resolution degrades to the look of a device that never offered this choice.
         */
        SYSTEM("system", "System accent", R.style.Accent_System, 0xFF3B9EFF.toInt(), 0xFF6DB8FF.toInt());

        companion object {
            fun fromKey(key: String?): Choice = entries.firstOrNull { it.key == key } ?: AZURE
        }
    }

    val choices: List<Choice> get() = Choice.entries.toList()

    /**
     * What the picker lists: every fixed swatch, plus [Choice.SYSTEM] only on a device that supplies a
     * dynamic accent worth offering. Hiding it is the whole point — a choice that paints Azure is worse
     * than no choice, because it promises the user's own palette and delivers the app's.
     */
    fun choicesFor(context: Context): List<Choice> =
        if (isSystemAccentAvailable(context)) choices else choices.filterNot { it == Choice.SYSTEM }

    /** Cached because the probe is a resource read and the picker asks it every time it opens. */
    @Volatile private var systemAccentAvailability: Boolean? = null

    /**
     * True when this OS both defines the framework's dynamic accent tones and fills one in. The version
     * check alone is not enough: an AOSP build or an OEM overlay can leave the tone unresolved, and the
     * alpha half rejects a read that came back transparent or empty.
     */
    fun isSystemAccentAvailable(context: Context): Boolean {
        systemAccentAvailability?.let { return it }
        val available = systemAccentSupported(Build.VERSION.SDK_INT) &&
            usableAccent(readDynamicAccent(context) ?: 0) != null
        systemAccentAvailability = available
        return available
    }

    /** The version half of [isSystemAccentAvailable], split out so a host test can drive it. */
    internal fun systemAccentSupported(sdkInt: Int): Boolean = sdkInt >= SystemAccentMinSdk

    /** The value half: a tone that arrived is only usable if the framework gave it full opacity. */
    internal fun usableAccent(@ColorInt color: Int): Int? =
        if ((color ushr 24) == 0xFF) color else null

    /**
     * The accent in force for [activity], read back out of the overlay it applied rather than from the
     * enum literal. Identical for the fixed swatches, whose overlay sets exactly those constants; for
     * [Choice.SYSTEM] it is the tone the framework resolved, which no compile-time constant can hold.
     */
    @ColorInt fun solid(activity: Activity): Int = DesignTokens.accent(activity)

    /** Light partner of [solid], resolved the same way. */
    @ColorInt fun light(activity: Activity): Int = DesignTokens.accentLight(activity)

    /** The picker dot for [choice]: its constant, except for [Choice.SYSTEM], which has no constant. */
    @ColorInt fun swatchColor(context: Context, choice: Choice): Int =
        if (choice == Choice.SYSTEM) readDynamicAccent(context) ?: choice.solid else choice.solid

    /**
     * The accent in force, with [Choice.SYSTEM] dropped when this device cannot keep it and a paid choice
     * dropped when the unlock is not held. The paid half is resolved here rather than only in the picker
     * because this is what [apply] reads on every activity's way in: a stored Rose arriving through Auto
     * Backup, or bought and then refunded, must not paint the app in a color the user no longer owns.
     * The stored key itself is left alone, so buying unlocks it again without a re-pick.
     */
    fun current(activity: Activity): Choice {
        val stored = resolveChoice(IndexPreferences.getAccentColor(activity), isSystemAccentAvailable(activity))
        return if (FreeTier.accentLocked(stored, Entitlements.isUnlocked(activity))) Choice.AZURE else stored
    }

    /**
     * [Choice.fromKey] plus the availability veto. The stored key is checked rather than trusted: a
     * preference written on an Android 12 device reaches older ones through Auto Backup and device
     * transfer, and the overlay it names is only Azure in disguise there.
     */
    internal fun resolveChoice(key: String?, systemAccentAvailable: Boolean): Choice {
        val choice = Choice.fromKey(key)
        return if (choice == Choice.SYSTEM && !systemAccentAvailable) Choice.AZURE else choice
    }

    /** Applies the user's chosen accent overlay to [activity]. Must be called in `onCreate` before
     *  `super.onCreate(...)` so views inflate with the resolved accent. */
    fun apply(activity: Activity) {
        apply(activity, current(activity))
    }

    /** Applies a specific [choice] overlay to [activity]. */
    fun apply(activity: Activity, choice: Choice) {
        activity.theme.applyStyle(choice.overlayStyle, true)
    }

    /**
     * One framework tone, standing in for the whole dynamic palette: the platform rewrites every
     * `system_accent1_*` value per user when the wallpaper palette changes, so any of them proves the
     * same thing. Null rather than a default on a miss, so [isSystemAccentAvailable] can tell "absent"
     * from "present but grey".
     */
    private fun readDynamicAccent(context: Context): Int? = runCatching {
        ContextCompat.getColor(context, android.R.color.system_accent1_400)
    }.getOrNull()

    /** Android 12 introduced the dynamic accent tones this choice reads. Kept as a number so a host
     *  test can compare against it without loading `android.os.Build`. */
    private const val SystemAccentMinSdk = 31
}
