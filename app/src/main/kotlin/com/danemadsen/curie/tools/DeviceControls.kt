package com.danemadsen.curie.tools

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

internal enum class TorchResult {
    SWITCHED,
    NO_FLASH,

    /** Another app is using the camera, or the camera service refused. */
    UNAVAILABLE,
}

/**
 * The actions that call a system service directly instead of starting an activity, so they also
 * work while a call runs in the background. None needs a permission. On Android 17 a volume change
 * from the background also needs a running foreground service, which a call has.
 */
internal class DeviceControls(private val context: Context) {
    private val audioManager: AudioManager?
        get() = context.getSystemService(AudioManager::class.java)

    /** The torch stays on only while Curie's process is alive; Android turns it off when the app exits. */
    fun setTorch(on: Boolean): TorchResult {
        val cameraManager = context.getSystemService(CameraManager::class.java) ?: return TorchResult.NO_FLASH

        return try {
            val cameraId = findFlashCamera(cameraManager) ?: return TorchResult.NO_FLASH
            cameraManager.setTorchMode(cameraId, on)
            TorchResult.SWITCHED
        } catch (exception: CameraAccessException) {
            Log.w(TAG, "The torch could not be switched", exception)
            TorchResult.UNAVAILABLE
        } catch (exception: IllegalArgumentException) {
            Log.w(TAG, "The camera has no torch", exception)
            TorchResult.NO_FLASH
        }
    }

    /** Prefers the back camera's flash, which is the one people call the flashlight. */
    private fun findFlashCamera(cameraManager: CameraManager): String? {
        var anyFlashCamera: String? = null
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val hasFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            if (!hasFlash) {
                continue
            }

            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                return cameraId
            }
            if (anyFlashCamera == null) {
                anyFlashCamera = cameraId
            }
        }
        return anyFlashCamera
    }

    /**
     * Sets the media volume and returns the percentage it actually has afterwards, or null when the
     * device does not let apps change it (fixed volume) or refused the change.
     */
    fun setMediaVolume(percent: Int): Int? {
        val manager = audioManager ?: return null
        if (manager.isVolumeFixed) {
            return null
        }

        val minIndex = manager.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val maxIndex = manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val indexRange = maxIndex - minIndex
        if (indexRange <= 0) {
            return null
        }

        val targetIndex = minIndex + (indexRange * percent / 100.0).roundToInt()
        try {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, targetIndex, AudioManager.FLAG_SHOW_UI)
        } catch (exception: SecurityException) {
            // Thrown when the change would toggle Do Not Disturb without notification policy access.
            Log.w(TAG, "The volume change was refused", exception)
            return null
        }

        // Android 17 ignores volume changes from the background silently, so check what happened.
        val actualIndex = manager.getStreamVolume(AudioManager.STREAM_MUSIC)
        if (actualIndex != targetIndex) {
            return null
        }
        return ((actualIndex - minIndex) * 100.0 / indexRange).roundToInt()
    }

    /** Sends a media key press (down then up) to the app that last played media. */
    fun pressMediaKey(keyCode: Int) {
        val manager = audioManager ?: return
        val eventTime = SystemClock.uptimeMillis()
        val keyDown = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val keyUp = KeyEvent(eventTime, eventTime, KeyEvent.ACTION_UP, keyCode, 0)

        manager.dispatchMediaKeyEvent(keyDown)
        manager.dispatchMediaKeyEvent(keyUp)
    }

    /** The battery charge in percent, or null when the device does not report it. */
    fun batteryPercent(): Int? {
        val batteryManager = context.getSystemService(BatteryManager::class.java)
        val capacity = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacity != null && capacity in 0..100) {
            return capacity
        }
        return batteryPercentFromStickyBroadcast()
    }

    fun isCharging(): Boolean {
        val batteryManager = context.getSystemService(BatteryManager::class.java) ?: return false
        return batteryManager.isCharging
    }

    private fun batteryPercentFromStickyBroadcast(): Int? {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = ContextCompat.registerReceiver(
            context,
            null,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        ) ?: return null

        val level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) {
            return null
        }
        return level * 100 / scale
    }

    private companion object {
        const val TAG = "DeviceControls"
    }
}
