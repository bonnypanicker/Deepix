package com.devomind.gallerysearch

/**
 * Framework-free snapshot of the device state used to decide whether the CLIP indexer should
 * run, wait, or change how aggressively it uses CPU/RAM.
 */
data class IndexHardwareState(
    val isCharging: Boolean,
    val isBatteryLow: Boolean,
    val isStorageLow: Boolean,
    val isPowerSaveMode: Boolean,
    val isInteractive: Boolean,
    val isDeviceIdle: Boolean,
    val isDeviceLightIdle: Boolean,
    val thermalStatus: ThermalStatus,
    val thermalHeadroom: Float?,
    val hourOfDay: Int,
    /** Battery sensor reading in °C, or null when the device reported nothing usable. */
    val batteryTemperatureC: Float? = null,
    /** `BATTERY_HEALTH_OVERHEAT` from the battery broadcast — the platform's own verdict. */
    val isBatteryOverheating: Boolean = false,
    /** Screen on and input within the quiet window: someone is using the device right now. */
    val isUserInteracting: Boolean = false
)

enum class ThermalStatus {
    Unknown,
    None,
    Light,
    Moderate,
    Severe,
    Critical,
    Emergency,
    Shutdown
}

enum class IndexRunProfile {
    Normal,
    High,
    Max,

    /** Deliberately reduced: a warm cell, `Moderate` thermal status, or battery saver asked for it. */
    Cooldown,

    /** Someone has the phone in hand: smallest batch, and a forced gap between batches. */
    Quiet
}

enum class IndexWaitReason {
    WaitingForCharge,
    WaitingForUserIdle,
    WaitingForThermalCooldown,
    WaitingForBattery,
    WaitingForStorage,
    WaitingForNightWindow,
    WaitingForCompression,
    /** The app's 6 h of `dataSync` foreground time is spent; see [ForegroundBudget]. */
    WaitingForForegroundBudget
}

sealed class IndexRunDecision {
    data class Run(val profile: IndexRunProfile) : IndexRunDecision()
    data class Wait(val reason: IndexWaitReason) : IndexRunDecision()
}

data class IndexRunRequirements(
    val requiresCharging: Boolean,
    val requiresNightWindow: Boolean,
    val requiresUserIdle: Boolean,
    val requiresBatteryNotLow: Boolean,
    val requiresStorageNotLow: Boolean
)

object IndexRunPolicy {
    fun decide(state: IndexHardwareState, requirements: IndexRunRequirements): IndexRunDecision {
        if (requirements.requiresStorageNotLow && state.isStorageLow) {
            return IndexRunDecision.Wait(IndexWaitReason.WaitingForStorage)
        }
        if (requirements.requiresBatteryNotLow && state.isBatteryLow && !state.isCharging) {
            return IndexRunDecision.Wait(IndexWaitReason.WaitingForBattery)
        }
        if (requirements.requiresCharging && !state.isCharging) {
            return IndexRunDecision.Wait(IndexWaitReason.WaitingForCharge)
        }
        if (requirements.requiresNightWindow &&
            !IndexPreferences.isNightChargeHour(state.hourOfDay)
        ) {
            return IndexRunDecision.Wait(IndexWaitReason.WaitingForNightWindow)
        }
        if (requirements.requiresUserIdle && state.isInteractive && !state.isDeviceIdle) {
            return IndexRunDecision.Wait(IndexWaitReason.WaitingForUserIdle)
        }
        if (state.thermalStatus.blocksIndexing() || state.isHeadroomSevere() ||
            state.isBatteryTooHot()
        ) {
            return IndexRunDecision.Wait(IndexWaitReason.WaitingForThermalCooldown)
        }

        val profile = when {
            // Ahead of the thermal tiers: a run that stops and restarts every time the user wakes
            // the screen pays reconciliation again, so an in-hand device slows the run instead.
            state.isUserInteracting -> IndexRunProfile.Quiet
            state.thermalStatus == ThermalStatus.Moderate || state.isPowerSaveMode ||
                state.isBatteryWarm() -> {
                IndexRunProfile.Cooldown
            }
            state.isCharging &&
                (!state.isInteractive || state.isDeviceIdle || state.isDeviceLightIdle) &&
                state.isCoolEnoughForBoost() -> {
                IndexRunProfile.Max
            }
            (state.isCharging || !state.isInteractive) && state.isCoolEnoughForBoost() -> {
                IndexRunProfile.High
            }
            else -> IndexRunProfile.Normal
        }
        return IndexRunDecision.Run(profile)
    }

    fun waitReasonLabel(reason: IndexWaitReason?): String = when (reason) {
        IndexWaitReason.WaitingForCharge -> "Waiting for charger"
        IndexWaitReason.WaitingForUserIdle -> "Waiting for screen off"
        IndexWaitReason.WaitingForThermalCooldown -> "Cooling down"
        IndexWaitReason.WaitingForBattery -> "Waiting for battery"
        IndexWaitReason.WaitingForStorage -> "Waiting for storage"
        IndexWaitReason.WaitingForNightWindow -> "Waiting for night"
        IndexWaitReason.WaitingForCompression -> "Waiting for compression"
        IndexWaitReason.WaitingForForegroundBudget -> "Waiting for today's background time"
        null -> ""
    }

    /**
     * The probe line shown while indexing runs. Its buckets mirror the tiers in [decide] on purpose:
     * "normal" has to mean "the run is at its untuned profile", not merely "nothing is in danger", or a
     * 41 °C cell reads as calm next to the number the same label prints.
     */
    fun deviceStateLabel(state: IndexHardwareState): String {
        val power = if (state.isCharging) "charging" else "not charging"
        val screen = when {
            state.isUserInteracting -> "in hand"
            state.isDeviceIdle || state.isDeviceLightIdle -> "idle"
            state.isInteractive -> "screen active"
            else -> "screen off"
        }
        val thermal = when {
            state.thermalStatus.blocksIndexing() || state.isHeadroomSevere() ||
                state.isBatteryTooHot() -> "hot"
            state.thermalStatus == ThermalStatus.Moderate || state.isPowerSaveMode ||
                state.isBatteryWarm() -> "warm"
            else -> "normal"
        }
        val temperature = state.batteryTemperatureC?.let { " · ${it.toInt()}°C" }.orEmpty()
        return "$power · $screen · thermal $thermal$temperature"
    }

    /**
     * Boost tiers need positive evidence that the device is cool. `Unknown` thermal status — pre-API-29,
     * or an OEM that never reports it — no longer reads as "cool"; the battery sensor has to say so.
     * Without either signal the run still proceeds, just at the untuned profile.
     *
     * A warm cell also disqualifies a boost on devices that *do* report thermal status: the charger is
     * itself a heat source, so the SoC's report can sit at `None` while the cell climbs past 40 °C.
     * Since the aggressive tiers are the ones gated here, a charging pass paces itself down as the
     * charge warms the battery instead of running flat out for the whole time it is plugged in.
     */
    private fun IndexHardwareState.isCoolEnoughForBoost(): Boolean {
        if (thermalStatus.blocksIndexing() || isBatteryTooHot()) return false
        if (batteryTemperatureC?.let { it >= MaxBoostBatteryTempC } == true) return false
        return when (thermalStatus) {
            ThermalStatus.Unknown -> batteryTemperatureC != null
            else -> thermalStatus == ThermalStatus.None || thermalStatus == ThermalStatus.Light
        }
    }

    /** The platform's own overheat verdict, or a battery cell reading past the safety ceiling. */
    private fun IndexHardwareState.isBatteryTooHot(): Boolean =
        isBatteryOverheating || (batteryTemperatureC ?: 0f) >= HotBatteryTempC

    /**
     * A cell this warm is the run's own cue to ease off, not merely grounds for refusing a boost.
     * `currentThermalStatus` reads a board sensor and routinely stays at `None` while the charger pushes
     * the battery past 40 °C, so without this the pass sat at its untuned profile for the whole warm
     * band between the boost ceiling and the stop — hot, and reporting itself as normal.
     */
    private fun IndexHardwareState.isBatteryWarm(): Boolean =
        batteryTemperatureC?.let { it >= WarmBatteryTempC } == true

    private fun ThermalStatus.blocksIndexing(): Boolean =
        this == ThermalStatus.Severe ||
            this == ThermalStatus.Critical ||
            this == ThermalStatus.Emergency ||
            this == ThermalStatus.Shutdown

    private fun IndexHardwareState.isHeadroomSevere(): Boolean =
        thermalHeadroom?.let { it >= SevereThermalHeadroom } == true

    private const val SevereThermalHeadroom = 1.0f

    /** Stop indexing above this cell temperature. Li-ion warns near 45 °C, so this is deliberately
     *  early — the job is background work and has no reason to compete with heat. */
    private const val HotBatteryTempC = 42f

    /** Two degrees below the stop: the band where the run is not in danger but has no margin left
     *  either, so it takes the reduced profile instead of waiting for [HotBatteryTempC] to halt it. */
    private const val WarmBatteryTempC = 40f

    /** Boost only when the cell is already comfortably cool. Charging lifts it a few degrees on its
     *  own, so this is what steps an overnight pass back down mid-run: being plugged in is not a signal
     *  to go flat out, it is a signal that the cell has an extra heat source for the next hour. */
    private const val MaxBoostBatteryTempC = 36f
}
