package com.devomind.gallerysearch

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import java.util.Calendar

object IndexHardwareMonitor {
    /**
     * `elapsedRealtime` of the last touch the app received, stamped by the foreground Activity. Drives
     * the Quiet profile: indexing slows while someone is using the phone instead of stopping.
     */
    @Volatile private var lastUserInteractionRealtime: Long = 0L

    fun noteUserInteraction() {
        lastUserInteractionRealtime = SystemClock.elapsedRealtime()
    }

    fun snapshot(context: Context): IndexHardwareState {
        val appContext = context.applicationContext
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
        val isInteractive = powerManager.isInteractive
        return IndexHardwareState(
            isCharging = battery.isCharging(),
            isBatteryLow = battery.isBatteryLow(),
            isStorageLow = appContext.isStorageLow(),
            isPowerSaveMode = powerManager.isPowerSaveMode,
            isInteractive = isInteractive,
            isDeviceIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isDeviceIdleMode
            } else {
                false
            },
            isDeviceLightIdle = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                powerManager.isDeviceLightIdleMode
            } else {
                false
            },
            thermalStatus = powerManager.currentThermalStatusCompat(),
            thermalHeadroom = powerManager.thermalHeadroomCompat(),
            hourOfDay = Calendar.getInstance().get(Calendar.HOUR_OF_DAY),
            batteryTemperatureC = battery.temperatureC(),
            isBatteryOverheating = battery.isOverheating(),
            isUserInteracting = isInteractive &&
                SystemClock.elapsedRealtime() - lastUserInteractionRealtime < InteractionQuietMillis
        )
    }

    fun requirements(context: Context): IndexRunRequirements =
        IndexRunRequirements(
            requiresCharging = IndexPreferences.isChargingOnlyIndexing(context),
            requiresNightWindow = IndexPreferences.isChargingOnlyIndexing(context) &&
                IndexPreferences.isNightChargingOnly(context),
            requiresUserIdle = IndexPreferences.isUserIdleRequiredForIndexing(context),
            requiresBatteryNotLow = IndexPreferences.isBatteryNotLowRequiredForIndexing(context),
            requiresStorageNotLow = IndexPreferences.isStorageNotLowRequiredForIndexing(context)
        )

    fun decision(context: Context): Pair<IndexHardwareState, IndexRunDecision> {
        val state = snapshot(context)
        return state to IndexRunPolicy.decide(state, requirements(context))
    }

    private fun Intent?.isCharging(): Boolean {
        if (this == null) return false
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val hasPowerCable =
            plugged == BatteryManager.BATTERY_PLUGGED_AC ||
                plugged == BatteryManager.BATTERY_PLUGGED_USB ||
                plugged == BatteryManager.BATTERY_PLUGGED_WIRELESS
        return hasPowerCable ||
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun Intent?.isBatteryLow(): Boolean {
        if (this == null) return false
        val status = getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        if (status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        ) {
            return false
        }
        val level = getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return false
        return (level * 100 / scale) <= LowBatteryPercent
    }

    /**
     * Battery probe in °C. `EXTRA_TEMPERATURE` is in tenths of a degree, and some vendors report 0
     * when the sensor is unavailable — a plausible-range filter turns that into "no reading" instead
     * of a freezing-cold battery that would justify boosting.
     */
    private fun Intent?.temperatureC(): Float? {
        if (this == null) return null
        val tenths = getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
        if (tenths <= 0) return null
        val celsius = tenths / 10f
        return if (celsius in MinPlausibleBatteryTempC..MaxPlausibleBatteryTempC) celsius else null
    }

    private fun Intent?.isOverheating(): Boolean {
        if (this == null) return false
        return getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ==
            BatteryManager.BATTERY_HEALTH_OVERHEAT
    }

    private fun Context.isStorageLow(): Boolean =
        registerReceiver(null, IntentFilter(Intent.ACTION_DEVICE_STORAGE_LOW)) != null

    private fun PowerManager.currentThermalStatusCompat(): ThermalStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return ThermalStatus.Unknown
        return when (currentThermalStatus) {
            PowerManager.THERMAL_STATUS_NONE -> ThermalStatus.None
            PowerManager.THERMAL_STATUS_LIGHT -> ThermalStatus.Light
            PowerManager.THERMAL_STATUS_MODERATE -> ThermalStatus.Moderate
            PowerManager.THERMAL_STATUS_SEVERE -> ThermalStatus.Severe
            PowerManager.THERMAL_STATUS_CRITICAL -> ThermalStatus.Critical
            PowerManager.THERMAL_STATUS_EMERGENCY -> ThermalStatus.Emergency
            PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalStatus.Shutdown
            else -> ThermalStatus.Unknown
        }
    }

    private fun PowerManager.thermalHeadroomCompat(): Float? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return runCatching { getThermalHeadroom(ThermalForecastSeconds) }
            .getOrNull()
            ?.takeUnless { it.isNaN() }
    }

    private const val LowBatteryPercent = 15
    private const val ThermalForecastSeconds = 10
    private const val MinPlausibleBatteryTempC = 5f
    private const val MaxPlausibleBatteryTempC = 60f

    /** How recently a touch must land for indexing to pace itself behind the user. */
    private const val InteractionQuietMillis = 15_000L
}
