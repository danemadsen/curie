package com.danemadsen.curie.assistant.wakeword

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/** Why listening pauses by itself. */
internal enum class PauseReason {
    TOO_HOT,
    BATTERY_SAVER,
    LOW_BATTERY,

    /** Curie's own call or voice message has the microphone. */
    MICROPHONE_IN_USE,

    /** Android silenced Curie's recording because another app records. */
    MICROPHONE_SILENCED,
}

/** The device state that decides whether continuous listening is reasonable right now. */
internal data class DeviceConditions(
    /** A PowerManager.THERMAL_STATUS_* value. */
    val thermalStatus: Int,

    val isPowerSaveMode: Boolean,

    /** Null when the device does not report its charge. */
    val batteryPercent: Int?,

    val isPluggedIn: Boolean,
) {
    /** Why listening should pause, or null when it may run. */
    fun pauseReason(): PauseReason? {
        val isTooHot = thermalStatus >= PowerManager.THERMAL_STATUS_SEVERE
        val isLowBattery = batteryPercent != null && batteryPercent < LOW_BATTERY_PERCENT && !isPluggedIn
        return when {
            isTooHot -> PauseReason.TOO_HOT
            isPowerSaveMode -> PauseReason.BATTERY_SAVER
            isLowBattery -> PauseReason.LOW_BATTERY
            else -> null
        }
    }

    companion object {
        const val LOW_BATTERY_PERCENT = 15
    }
}

/**
 * The current [DeviceConditions] and every change: thermal status (API 29 listener), Battery Saver and
 * battery broadcasts. Collect it on the main thread; the callbacks arrive there.
 */
internal fun deviceConditions(context: Context): Flow<DeviceConditions> {
    val updates = callbackFlow {
        val powerManager = context.getSystemService(PowerManager::class.java)
        val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val stickyBatteryStatus = ContextCompat.registerReceiver(
            context,
            null,
            batteryFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        val thermalStatus = powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        val isPowerSaveMode = powerManager?.isPowerSaveMode ?: false
        val initialPercent = if (stickyBatteryStatus == null) null else batteryPercent(stickyBatteryStatus)
        val isInitiallyPluggedIn = stickyBatteryStatus != null && isPluggedIn(stickyBatteryStatus)

        var conditions = DeviceConditions(thermalStatus, isPowerSaveMode, initialPercent, isInitiallyPluggedIn)
        trySend(conditions)

        val thermalListener = PowerManager.OnThermalStatusChangedListener { status ->
            conditions = conditions.copy(thermalStatus = status)
            trySend(conditions)
        }
        powerManager?.addThermalStatusListener(context.mainExecutor, thermalListener)

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context, intent: Intent) {
                conditions = when (intent.action) {
                    PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                        val isPowerSaveMode = powerManager?.isPowerSaveMode ?: false
                        conditions.copy(isPowerSaveMode = isPowerSaveMode)
                    }

                    Intent.ACTION_BATTERY_CHANGED -> {
                        val percent = batteryPercent(intent)
                        val pluggedIn = isPluggedIn(intent)
                        conditions.copy(batteryPercent = percent, isPluggedIn = pluggedIn)
                    }

                    else -> conditions
                }
                trySend(conditions)
            }
        }
        val filter = IntentFilter()
        filter.addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        filter.addAction(Intent.ACTION_BATTERY_CHANGED)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        awaitClose {
            powerManager?.removeThermalStatusListener(thermalListener)
            context.unregisterReceiver(receiver)
        }
    }

    // ACTION_BATTERY_CHANGED also fires for temperature and voltage changes.
    return updates.distinctUntilChanged()
}

private fun batteryPercent(batteryStatus: Intent): Int? {
    val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
    val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
    if (level < 0 || scale <= 0) {
        return null
    }
    return level * 100 / scale
}

private fun isPluggedIn(batteryStatus: Intent): Boolean {
    val plugged = batteryStatus.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
    return plugged != 0
}
