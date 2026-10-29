package com.danemadsen.curie.engine.media

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sqrt

/** The window functions of the spectral transforms and the resampler. */
internal object SignalWindows {
    /** torch.hann_window(length), the periodic Hann window: 0.5 - 0.5 cos(2πn / length). */
    fun periodicHann(length: Int): DoubleArray {
        require(length > 0) { "a window needs at least one sample, got $length" }
        return DoubleArray(length) { index -> 0.5 - 0.5 * cos(2 * PI * index / length) }
    }

    /**
     * The Kaiser window of shape [beta] at [position], which runs from -1 to 1 over the window:
     * I0(beta * sqrt(1 - position²)) / I0(beta), and zero outside.
     */
    fun kaiser(position: Double, beta: Double): Double {
        if (abs(position) > 1.0) {
            return 0.0
        }
        val argument = beta * sqrt(1.0 - position * position)
        return besselI0(argument) / besselI0(beta)
    }

    /** The modified Bessel function of the first kind of order 0: the sum of ((x / 2)^k / k!)². */
    private fun besselI0(x: Double): Double {
        val half = x / 2
        var term = 1.0
        var sum = 1.0
        var k = 1
        // The terms are positive, so the sum is exact to rounding once they stop contributing
        while (term > sum * 1e-17) {
            val factor = half / k
            term *= factor * factor
            sum += term
            k++
        }
        return sum
    }
}
