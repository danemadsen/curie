package com.danemadsen.curie.ui.call.eclipse

import kotlin.math.exp

/**
 * Follows an audio level like a VU meter's needle: it rises with the time constant [attackSeconds] and falls
 * with the slower [releaseSeconds], so the corona swells with each syllable and settles between them. Raw
 * levels are clamped to [0, 1], and NaN counts as silence.
 */
internal class LevelSmoother(private val attackSeconds: Float, private val releaseSeconds: Float) {
    var value = 0f
        private set

    /** Moves towards [level] for [elapsedSeconds] and returns the new smoothed value. */
    fun update(level: Float, elapsedSeconds: Float): Float {
        val target = if (level.isNaN()) 0f else level.coerceIn(0f, 1f)
        val timeConstant = if (target > value) attackSeconds else releaseSeconds
        val step = 1f - exp(-elapsedSeconds / timeConstant)
        value += (target - value) * step
        return value
    }
}
