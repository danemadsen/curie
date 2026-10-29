package com.danemadsen.curie.engine.media

import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FftTest {
    /** Every size up to 1280 whose prime factors are 2, 3 and 5, and the codec's and mel's sizes. */
    private val sizes = (1..1280).filter { size -> isFiveSmooth(size) }

    @Test
    fun `forward matches the naive DFT for every 5-smooth size up to 1280`() {
        val random = Random(1)
        for (size in sizes) {
            val signal = randomSignal(random, size)
            val expected = naiveDft(signal, inverse = false)

            val actual = signal.copy()
            Fft(size).forward(actual.re, actual.im)

            assertClose(expected, actual, "forward, size $size")
        }
    }

    @Test
    fun `inverse matches the naive inverse DFT scaled by 1 over n`() {
        val random = Random(2)
        for (size in sizes) {
            val spectrum = randomSignal(random, size)
            val expected = naiveDft(spectrum, inverse = true)

            val actual = spectrum.copy()
            Fft(size).inverse(actual.re, actual.im)

            assertClose(expected, actual, "inverse, size $size")
        }
    }

    @Test
    fun `inverse undoes forward`() {
        val random = Random(3)
        for (size in listOf(1, 2, 3, 5, 6, 15, 64, 400, 1280, 3000)) {
            val signal = randomSignal(random, size)
            val fft = Fft(size)

            val roundTrip = signal.copy()
            fft.forward(roundTrip.re, roundTrip.im)
            fft.inverse(roundTrip.re, roundTrip.im)

            assertClose(signal, roundTrip, "round trip, size $size")
        }
    }

    @Test
    fun `transforms a real cosine into two spectral lines`() {
        val size = 400
        val bin = 7
        val re = DoubleArray(size) { index -> cos(2 * PI * bin * index / size) }
        val im = DoubleArray(size)

        Fft(size).forward(re, im)

        for (index in 0 until size) {
            val expected = if (index == bin || index == size - bin) size / 2.0 else 0.0
            assertEquals(expected, re[index], 1e-9, "re[$index]")
            assertEquals(0.0, im[index], 1e-9, "im[$index]")
        }
    }

    @Test
    fun `rejects sizes with other prime factors`() {
        for (size in listOf(0, -4, 7, 14, 22, 1001)) {
            assertFailsWith<IllegalArgumentException>("size $size") { Fft(size) }
        }
    }

    @Test
    fun `rejects arrays of another length`() {
        val fft = Fft(8)
        assertFailsWith<IllegalArgumentException> { fft.forward(DoubleArray(8), DoubleArray(7)) }
        assertFailsWith<IllegalArgumentException> { fft.inverse(DoubleArray(9), DoubleArray(9)) }
    }

    private class Complex(val re: DoubleArray, val im: DoubleArray) {
        fun copy() = Complex(re.copyOf(), im.copyOf())
    }

    private fun randomSignal(random: Random, size: Int): Complex {
        val re = DoubleArray(size) { random.nextDouble(-1.0, 1.0) }
        val im = DoubleArray(size) { random.nextDouble(-1.0, 1.0) }
        return Complex(re, im)
    }

    /** The DFT by its definition, with exact twiddle factors e^(∓2πi j / n) for j = kt mod n. */
    private fun naiveDft(signal: Complex, inverse: Boolean): Complex {
        val size = signal.re.size
        val sign = if (inverse) 1.0 else -1.0
        val twiddleCos = DoubleArray(size) { index -> cos(2 * PI * index / size) }
        val twiddleSin = DoubleArray(size) { index -> sign * sin(2 * PI * index / size) }
        val scale = if (inverse) 1.0 / size else 1.0

        val re = DoubleArray(size)
        val im = DoubleArray(size)
        for (frequency in 0 until size) {
            var sumRe = 0.0
            var sumIm = 0.0
            for (time in 0 until size) {
                val twiddle = (frequency.toLong() * time % size).toInt()
                val cos = twiddleCos[twiddle]
                val sin = twiddleSin[twiddle]
                sumRe += signal.re[time] * cos - signal.im[time] * sin
                sumIm += signal.re[time] * sin + signal.im[time] * cos
            }
            re[frequency] = sumRe * scale
            im[frequency] = sumIm * scale
        }
        return Complex(re, im)
    }

    /** Within 1e-12 of the largest magnitude: the naive sums carry about n rounding errors. */
    private fun assertClose(expected: Complex, actual: Complex, message: String) {
        var largest = 0.0
        var worst = 0.0
        for (index in expected.re.indices) {
            largest = max(largest, max(abs(expected.re[index]), abs(expected.im[index])))
            worst = max(worst, abs(expected.re[index] - actual.re[index]))
            worst = max(worst, abs(expected.im[index] - actual.im[index]))
        }
        val tolerance = 1e-12 * max(largest, 1.0)
        assertTrue(worst <= tolerance, "$message: error $worst, largest value $largest")
    }

    private fun isFiveSmooth(size: Int): Boolean {
        var remaining = size
        for (prime in intArrayOf(2, 3, 5)) {
            while (remaining % prime == 0) {
                remaining /= prime
            }
        }
        return remaining == 1
    }
}
