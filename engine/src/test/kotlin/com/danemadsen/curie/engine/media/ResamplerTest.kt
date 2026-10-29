package com.danemadsen.curie.engine.media

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class ResamplerTest {
    /** The signals stay below 0.8 of the lower Nyquist frequency, where resample_poly itself is flat. */
    @TestFactory
    fun `agrees with scipy resample_poly on band-limited signals`(): List<DynamicTest> =
        goldenTests(MediaGolden.RESAMPLE_POLY, minimumSnr = 40.0)

    /**
     * soxr's HQ preset (librosa's default resampler) keeps the same band flat, so speech that fills
     * the band agrees with it closely; a resampler that rolled off early would not.
     */
    @TestFactory
    fun `agrees with soxr HQ on full-band speech`(): List<DynamicTest> =
        goldenTests(MediaGolden.SOXR_HQ, minimumSnr = 55.0)

    @Test
    fun `keeps the band up to 92 percent of the lower Nyquist frequency flat`() {
        val ratePairs = listOf(
            44_100 to 16_000,
            48_000 to 16_000,
            22_050 to 16_000,
            96_000 to 16_000,
            8_000 to 16_000,
            11_025 to 16_000,
            16_000 to 24_000,
        )
        for ((fromRate, toRate) in ratePairs) {
            val nyquist = minOf(fromRate, toRate) / 2.0
            for (fraction in listOf(0.1, 0.5, 0.8, 0.9, 0.92)) {
                val frequencies = doubleArrayOf(fraction * nyquist)
                val input = tones(frequencies, fromRate, count = fromRate / 4)

                val output = Resampler.resample(input, fromRate, toRate)

                // Away from the edges, 70 dB against the ideal tone bounds the gain error by 0.003 dB
                val ideal = tones(frequencies, toRate, count = output.size)
                val margin = toRate / 20
                val steadyOutput = output.copyOfRange(margin, output.size - margin)
                val steadyIdeal = ideal.copyOfRange(margin, ideal.size - margin)
                val snr = signalToNoise(steadyIdeal, steadyOutput)
                assertTrue(snr > 70.0, "$fromRate to $toRate, a tone at $fraction of Nyquist: SNR $snr dB")
            }
        }
    }

    @Test
    fun `suppresses what would alias`() {
        // Nothing above 8 kHz exists at 16 kHz: 8.16, 8.4, 9 and 15 kHz would fold back to 7.84, 7.6, 7 and 1 kHz
        for (frequency in listOf(8_160.0, 8_400.0, 9_000.0, 15_000.0)) {
            val input = tones(doubleArrayOf(frequency), 44_100, count = 8_820)

            val output = Resampler.resample(input, 44_100, 16_000)

            val steady = output.copyOfRange(800, output.size - 800)
            val level = 20 * log10(rms(steady) / rms(input))
            assertTrue(level < -75.0, "$frequency Hz: $level dB")
        }
    }

    @Test
    fun `output length is the ceiling of n times to over from`() {
        val rates = listOf(8_000, 11_025, 16_000, 22_050, 24_000, 44_100, 48_000, 96_000, 44_101)
        val counts = listOf(0, 1, 2, 3, 159, 160, 441, 1_000, 4_417)
        for (fromRate in rates) {
            for (toRate in rates) {
                for (count in counts) {
                    // ceil(a / b) = -floor(-a / b)
                    val expected = -Math.floorDiv(-count.toLong() * toRate, fromRate.toLong())
                    val length = Resampler.outputLength(count, fromRate, toRate)
                    assertEquals(expected, length.toLong(), "$count samples from $fromRate to $toRate")
                }
            }
        }

        val samples = FloatArray(1_234) { index -> sin(index * 0.01).toFloat() }
        for ((fromRate, toRate) in listOf(44_100 to 16_000, 8_000 to 16_000, 44_101 to 16_000, 16_000 to 96_000)) {
            val output = Resampler.resample(samples, fromRate, toRate)
            val expectedLength = Resampler.outputLength(samples.size, fromRate, toRate)
            assertEquals(expectedLength, output.size)
        }
    }

    @Test
    fun `is deterministic and leaves equal rates alone`() {
        val samples = FloatArray(5_000) { index -> sin(index * 0.3).toFloat() * 0.5f }
        val first = Resampler.resample(samples, 44_100, 16_000)
        val second = Resampler.resample(samples, 44_100, 16_000)
        assertContentEquals(first, second)

        val copy = Resampler.resample(samples, 16_000, 16_000)
        assertContentEquals(samples, copy)
        assertNotSame(samples, copy)
        val empty = Resampler.resample(FloatArray(0), 48_000, 16_000)
        assertEquals(0, empty.size)
    }

    @Test
    fun `mixes channels by their mean`() {
        val stereo = floatArrayOf(1f, 0f, 0.5f, -0.5f, -1f, 0.25f)
        val mixedStereo = Resampler.toMono(stereo, 2)
        assertContentEquals(floatArrayOf(0.5f, 0f, -0.375f), mixedStereo)

        val mono = floatArrayOf(0.1f, 0.2f)
        val copy = Resampler.toMono(mono, 1)
        assertContentEquals(mono, copy)
        assertNotSame(mono, copy)

        val surround = FloatArray(12) { index -> index.toFloat() }
        val mixedSurround = Resampler.toMono(surround, 6)
        assertContentEquals(floatArrayOf(2.5f, 8.5f), mixedSurround)
    }

    @Test
    fun `rejects invalid input`() {
        assertFailsWith<IllegalArgumentException> { Resampler.toMono(FloatArray(3), 2) }
        assertFailsWith<IllegalArgumentException> { Resampler.toMono(FloatArray(2), 0) }
        assertFailsWith<IllegalArgumentException> { Resampler.resample(FloatArray(2), 0, 16_000) }
        assertFailsWith<IllegalArgumentException> { Resampler.resample(FloatArray(2), 16_000, -1) }
    }

    /** One dynamic test per golden case of [reference]: same length, and an SNR over [minimumSnr] dB. */
    private fun goldenTests(reference: String, minimumSnr: Double): List<DynamicTest> {
        val cases = MediaGolden.manifest.resample.filter { case -> case.reference == reference }
        check(cases.isNotEmpty()) { "the golden data has no $reference cases" }

        return cases.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val input = MediaGolden.floats(case.input)
                val expected = MediaGolden.floats(case.expected)
                assertEquals(case.inputLength, input.size)

                val output = Resampler.resample(input, case.fromRate, case.toRate)

                assertEquals(case.expectedLength, output.size)
                val snr = signalToNoise(expected, output)
                assertTrue(snr > minimumSnr, "${case.name}: SNR $snr dB against $reference")
            }
        }
    }

    private fun tones(frequencies: DoubleArray, rate: Int, count: Int): FloatArray {
        val amplitude = 0.8 / frequencies.size
        return FloatArray(count) { index ->
            var value = 0.0
            for ((tone, frequency) in frequencies.withIndex()) {
                value += amplitude * sin(2 * PI * frequency * index / rate + tone)
            }
            value.toFloat()
        }
    }

    private fun signalToNoise(reference: FloatArray, test: FloatArray): Double {
        assertEquals(reference.size, test.size)
        var signal = 0.0
        var noise = 0.0
        for (index in reference.indices) {
            val difference = reference[index].toDouble() - test[index]
            signal += reference[index].toDouble() * reference[index]
            noise += difference * difference
        }
        return 10 * log10(signal / noise)
    }

    private fun rms(samples: FloatArray): Double {
        var sum = 0.0
        for (sample in samples) {
            sum += sample.toDouble() * sample
        }
        return sqrt(sum / samples.size)
    }
}
