package com.devomind.gallerysearch

import android.app.ActivityManager
import android.content.Context

/**
 * Single source of truth for the device-scaled index batch size used by [GalleryRepository].
 */
object BatchSizing {
    /** Highest batch this device's memory tier allows, before any profile boost or OOM clamp. */
    fun deviceBatchCeiling(context: Context): Int = deviceBatchCeiling(context.activityManager())

    /**
     * Images per inference batch: the device tier scaled by the thermal profile, then lowered — never
     * raised — by the OOM cap. The cap used to return early, which made every profile compute the
     * same number once a device had OOM'd.
     */
    fun computeBatchSize(
        context: Context,
        profile: IndexRunProfile = IndexRunProfile.Normal
    ): Int {
        val activityManager = context.activityManager()
        val base = when {
            activityManager.isLowRamDevice -> 2
            activityManager.memoryClass >= 192 -> 6
            else -> 4
        }
        val boosted = when (profile) {
            IndexRunProfile.Max -> base + 4
            IndexRunProfile.High -> base + 2
            IndexRunProfile.Normal -> base
            IndexRunProfile.Cooldown -> maxOf(2, base / 2)
            // Someone is holding the phone: smallest batch, plus [pacingDelayMillis] between them.
            IndexRunProfile.Quiet -> 2
        }
        val profiled = boosted.coerceIn(1, deviceBatchCeiling(activityManager))
        val oomCap = IndexPreferences.getOomBatchCap(context)
        return if (oomCap > 0) minOf(profiled, oomCap) else profiled
    }

    fun computeDecodeConcurrency(
        context: Context,
        batchSize: Int,
        profile: IndexRunProfile = IndexRunProfile.Normal
    ): Int {
        val activityManager = context.activityManager()
        if (activityManager.isLowRamDevice) return 1
        val cores = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        val cap = when (profile) {
            IndexRunProfile.Max -> 6
            IndexRunProfile.High -> 5
            IndexRunProfile.Normal -> 4
            IndexRunProfile.Cooldown -> 2
            IndexRunProfile.Quiet -> 1
        }
        return minOf(cores, batchSize, cap)
    }

    /**
     * Idle time forced between batches. Every profile gets some: a run of inference back-to-back holds
     * the governor at full clock for hours, and a short gap after each batch lets it drop and lets the
     * package cool, which is what keeps the run out of the thermal wait that costs far more time than
     * the pause does. The gap also idles the decode pool, which only runs ahead of the consumer by
     * [GalleryRepository]'s channel depth.
     *
     * The aggressive tiers are the *small* pauses, not the absent ones: charging is itself a heat source,
     * so the cell arrives at the charger already warm and IndexRunPolicy only lets it boost while the
     * reading says so. A pass that drifts up through 36 °C falls to [IndexRunProfile.Normal] and its
     * 900 ms gap on its own, without a wait and a restart.
     *
     * [IndexRunProfile.Quiet] is the exception in kind, not just degree — it exists to hand CPU back to
     * a foreground user rather than to manage heat.
     */
    fun pacingDelayMillis(profile: IndexRunProfile): Long = when (profile) {
        IndexRunProfile.Max -> 300L
        IndexRunProfile.High -> 600L
        IndexRunProfile.Normal -> 900L
        IndexRunProfile.Cooldown -> 1_400L
        IndexRunProfile.Quiet -> QuietPacingMillis
    }

    private fun Context.activityManager(): ActivityManager =
        getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private fun deviceBatchCeiling(activityManager: ActivityManager): Int = when {
        activityManager.isLowRamDevice -> 2
        activityManager.memoryClass >= 256 -> 10
        activityManager.memoryClass >= 192 -> 8
        else -> 6
    }

    private const val QuietPacingMillis = 2_000L
}
