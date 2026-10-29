package com.danemadsen.curie.assistant.wakeword

import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceConditionsTest {
    private val normal = DeviceConditions(
        thermalStatus = PowerManager.THERMAL_STATUS_NONE,
        isPowerSaveMode = false,
        batteryPercent = 80,
        isPluggedIn = false,
    )

    @Test
    fun normalConditionsAllowListening() {
        assertNull(normal.pauseReason())
    }

    @Test
    fun pausesFromSevereThermalStatus() {
        val moderate = normal.copy(thermalStatus = PowerManager.THERMAL_STATUS_MODERATE)
        val severe = normal.copy(thermalStatus = PowerManager.THERMAL_STATUS_SEVERE)
        val critical = normal.copy(thermalStatus = PowerManager.THERMAL_STATUS_CRITICAL)

        assertNull(moderate.pauseReason())
        assertEquals(PauseReason.TOO_HOT, severe.pauseReason())
        assertEquals(PauseReason.TOO_HOT, critical.pauseReason())
    }

    @Test
    fun pausesWithBatterySaver() {
        val saving = normal.copy(isPowerSaveMode = true)

        assertEquals(PauseReason.BATTERY_SAVER, saving.pauseReason())
    }

    @Test
    fun pausesBelowFifteenPercentUnlessPluggedIn() {
        val low = normal.copy(batteryPercent = 14)
        val lowCharging = low.copy(isPluggedIn = true)
        val atThreshold = normal.copy(batteryPercent = DeviceConditions.LOW_BATTERY_PERCENT)
        val unknown = normal.copy(batteryPercent = null)

        assertEquals(PauseReason.LOW_BATTERY, low.pauseReason())
        assertNull(lowCharging.pauseReason())
        assertNull(atThreshold.pauseReason())
        assertNull(unknown.pauseReason())
    }

    @Test
    fun heatWinsOverTheOtherReasons() {
        val everything = DeviceConditions(
            thermalStatus = PowerManager.THERMAL_STATUS_SEVERE,
            isPowerSaveMode = true,
            batteryPercent = 5,
            isPluggedIn = false,
        )

        assertEquals(PauseReason.TOO_HOT, everything.pauseReason())
    }
}
