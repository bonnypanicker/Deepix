package com.devomind.gallerysearch

import org.junit.Assert.assertEquals
import org.junit.Test

class IndexRunPolicyTest {
    @Test
    fun chargingOnlyUnpluggedWaits() {
        val decision = IndexRunPolicy.decide(
            state = baseState(isCharging = false),
            requirements = baseRequirements(requiresCharging = true)
        )

        assertEquals(IndexRunDecision.Wait(IndexWaitReason.WaitingForCharge), decision)
    }

    @Test
    fun nightWindowOutsideHoursWaits() {
        val decision = IndexRunPolicy.decide(
            state = baseState(isCharging = true, hourOfDay = 14),
            requirements = baseRequirements(requiresCharging = true, requiresNightWindow = true)
        )

        assertEquals(IndexRunDecision.Wait(IndexWaitReason.WaitingForNightWindow), decision)
    }

    @Test
    fun severeThermalWaits() {
        val decision = IndexRunPolicy.decide(
            state = baseState(thermalStatus = ThermalStatus.Severe),
            requirements = baseRequirements()
        )

        assertEquals(IndexRunDecision.Wait(IndexWaitReason.WaitingForThermalCooldown), decision)
    }

    @Test
    fun moderateThermalRunsCooldownProfile() {
        val decision = IndexRunPolicy.decide(
            state = baseState(thermalStatus = ThermalStatus.Moderate),
            requirements = baseRequirements()
        )

        assertEquals(IndexRunDecision.Run(IndexRunProfile.Cooldown), decision)
    }

    @Test
    fun screenOffChargingRunsMaxProfile() {
        val decision = IndexRunPolicy.decide(
            state = baseState(isCharging = true, isInteractive = false),
            requirements = baseRequirements()
        )

        assertEquals(IndexRunDecision.Run(IndexRunProfile.Max), decision)
    }

    @Test
    fun userActiveNoBlockersStillRuns() {
        val decision = IndexRunPolicy.decide(
            state = baseState(isCharging = false, isInteractive = true),
            requirements = baseRequirements()
        )

        assertEquals(IndexRunDecision.Run(IndexRunProfile.Normal), decision)
    }

    @Test
    fun warmChargingCellLosesTheBoost() {
        val decision = IndexRunPolicy.decide(
            state = baseState(isCharging = true, isInteractive = false, batteryTemperatureC = 37f),
            requirements = baseRequirements()
        )

        assertEquals(IndexRunDecision.Run(IndexRunProfile.Normal), decision)
    }

    @Test
    fun hotChargingCellRunsCooldownProfile() {
        val decision = IndexRunPolicy.decide(
            state = baseState(isCharging = true, isInteractive = false, batteryTemperatureC = 41f),
            requirements = baseRequirements()
        )

        assertEquals(IndexRunDecision.Run(IndexRunProfile.Cooldown), decision)
    }

    @Test
    fun overheatingCellWaits() {
        val decision = IndexRunPolicy.decide(
            state = baseState(isCharging = true, isInteractive = false, batteryTemperatureC = 43f),
            requirements = baseRequirements()
        )

        assertEquals(IndexRunDecision.Wait(IndexWaitReason.WaitingForThermalCooldown), decision)
    }

    private fun baseState(
        isCharging: Boolean = true,
        isBatteryLow: Boolean = false,
        isStorageLow: Boolean = false,
        isPowerSaveMode: Boolean = false,
        isInteractive: Boolean = true,
        isDeviceIdle: Boolean = false,
        isDeviceLightIdle: Boolean = false,
        thermalStatus: ThermalStatus = ThermalStatus.None,
        thermalHeadroom: Float? = null,
        hourOfDay: Int = 23,
        batteryTemperatureC: Float? = null
    ): IndexHardwareState =
        IndexHardwareState(
            isCharging = isCharging,
            isBatteryLow = isBatteryLow,
            isStorageLow = isStorageLow,
            isPowerSaveMode = isPowerSaveMode,
            isInteractive = isInteractive,
            isDeviceIdle = isDeviceIdle,
            isDeviceLightIdle = isDeviceLightIdle,
            thermalStatus = thermalStatus,
            thermalHeadroom = thermalHeadroom,
            hourOfDay = hourOfDay,
            batteryTemperatureC = batteryTemperatureC
        )

    private fun baseRequirements(
        requiresCharging: Boolean = false,
        requiresNightWindow: Boolean = false,
        requiresUserIdle: Boolean = false,
        requiresBatteryNotLow: Boolean = false,
        requiresStorageNotLow: Boolean = true
    ): IndexRunRequirements =
        IndexRunRequirements(
            requiresCharging = requiresCharging,
            requiresNightWindow = requiresNightWindow,
            requiresUserIdle = requiresUserIdle,
            requiresBatteryNotLow = requiresBatteryNotLow,
            requiresStorageNotLow = requiresStorageNotLow
        )
}
