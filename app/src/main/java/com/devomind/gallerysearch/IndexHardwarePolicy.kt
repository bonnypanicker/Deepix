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
    WaitingForCompression
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
            state.thermalStatus == ThermalStatus.Moderate || state.isPowerSaveMode -> {
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
        null -> ""
    }

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
            state.thermalStatus == ThermalStatus.Moderate || state.isPowerSaveMode -> "warm"
            else -> "normal"
        }
        val temperature = state.batteryTemperatureC?.let { " · ${it.toInt()}°C" }.orEmpty()
        return "$power · $screen · thermal $thermal$temperature"
    }

    /**
     * Boost tiers need positive evidence that the device is cool. `Unknown` — pre-API-29, or an OEM
     * that never reports thermal status — no longer reads as "cool"; the battery sensor has to say
     * so. Without either signal the run still proceeds, just at the untuned profile.
     */
    private fun IndexHardwareState.isCoolEnoughForBoost(): Boolean {
        if (thermalStatus.blocksIndexing() || isBatteryTooHot()) return false
        if (thermalStatus != ThermalStatus.Unknown) {
            return thermalStatus == ThermalStatus.None || thermalStatus == ThermalStatus.Light
        }
        return (batteryTemperatureC ?: HotBatteryTempC) < MaxBoostBatteryTempC
    }

    /** The platform's own overheat verdict, or a battery cell reading past the safety ceiling. */
    private fun IndexHardwareState.isBatteryTooHot(): Boolean =
        isBatteryOverheating || (batteryTemperatureC ?: 0f) >= HotBatteryTempC

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

    /** Boost only when the cell is already comfortably cool. */
    private const val MaxBoostBatteryTempC = 36f
}
