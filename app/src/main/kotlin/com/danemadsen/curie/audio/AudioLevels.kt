package com.danemadsen.curie.audio

import kotlin.math.log10
import kotlin.math.sqrt

/** Loudness measures of float samples in [-1, 1]. */
object AudioLevels {
    /** Levels at or below this read as silence (0) on the meter. */
    private const val METER_FLOOR_DB = -50f

    /** Levels at or above this read as full scale (1) on the meter. */
    private const val METER_CEILING_DB = -10f

    /** The quietest level [decibels] reports, for digital silence. */
    const val SILENCE_DB = -120f

    fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        if (to <= from) {
            return 0f
        }
        var sum = 0.0
        for (index in from until to) {
            val sample = samples[index].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / (to - from)).toFloat()
    }

    /** [rms] in dBFS (a full-scale sine is about -3 dB). */
    fun decibels(rms: Float): Float {
        if (rms <= 0f) {
            return SILENCE_DB
        }
        val decibels = 20f * log10(rms)
        return decibels.coerceAtLeast(SILENCE_DB)
    }

    /** A 0 to 1 meter reading of [rms]: -50 dBFS and below is 0, -10 dBFS and above is 1. */
    fun meter(rms: Float): Float {
        val decibels = decibels(rms)
        val fraction = (decibels - METER_FLOOR_DB) / (METER_CEILING_DB - METER_FLOOR_DB)
        return fraction.coerceIn(0f, 1f)
    }
}

/**
 * The latest audio level, written by an audio thread and read by the UI on every frame. A plain
 * volatile field: reading it costs nothing and never recomposes.
 */
class LevelMeter {
    @Volatile
    var level: Float = 0f
        private set

    fun update(rms: Float) {
        level = AudioLevels.meter(rms)
    }

    fun reset() {
        level = 0f
    }
}
