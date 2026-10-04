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
     * Idle time forced after a batch, in milliseconds of rest per image that batch contained. Every
     * profile asks for some: back-to-back inference holds the governor at full clock for hours, and the
     * pause lets it drop and the package cool, which keeps the run out of the thermal wait that costs far
     * more time than the pause does. The gap also idles the decode pool, which only runs ahead of the
     * consumer by [GalleryRepository]'s channel depth.
     *
     * The pause is proportional because the heat it answers to is: a batch of 10 deposits about ten
     * images' worth of energy and a batch of 2 deposits two, so a fixed pause per profile over-rests the
     * small bursts and under-rests the large ones. Keyed to the profile alone it was outright inverted on
     * any device whose batch collapses to 2 — low-RAM, a [deviceBatchCeiling] clamp, or an OOM cap — where
     * Cooldown's long fixed pause cooled more than Quiet's. Multiplying by the batch makes the rest track
     * the burst that earned it on every device.
     *
     * The coefficients put the longest rests behind the biggest bursts, which is where the heat is:
     * [IndexRunProfile.Max] waits 3.2 s after a batch of 10, [IndexRunProfile.Quiet] 1.0 s after a batch of
     * 2. Consequence to keep in mind — duty cycle is no longer ordered by tier, and Quiet is now busier
     * than Cooldown, so it yields the processor to a foreground user less than it used to.
     */
    fun pacingDelayMillis(profile: IndexRunProfile, batchSize: Int): Long =
        pacingMillisPerImage(profile) * batchSize.coerceAtLeast(1)

    private fun pacingMillisPerImage(profile: IndexRunProfile): Long = when (profile) {
        IndexRunProfile.Max -> 320L
        IndexRunProfile.High -> 375L
        IndexRunProfile.Normal -> 467L
        IndexRunProfile.Cooldown -> 600L
        IndexRunProfile.Quiet -> 500L
    }

    private fun Context.activityManager(): ActivityManager =
        getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    private fun deviceBatchCeiling(activityManager: ActivityManager): Int = when {
        activityManager.isLowRamDevice -> 2
        activityManager.memoryClass >= 256 -> 10
        activityManager.memoryClass >= 192 -> 8
        else -> 6
    }
}
