package com.danemadsen.curie.engine.media

import com.danemadsen.curie.engine.Lfm3Omni
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogMelSpectrogramTest {
    @Test
    fun `filter bank matches transformers mel_filter_bank to the last float32 bit`() {
        val golden = MediaGolden.manifest.melFilterBank
        val expected = MediaGolden.floats(golden.weights)
        val filterBank = LogMelSpectrogram.filterBank
        assertEquals(golden.frequencyBins, filterBank.frequencyBins)
        assertEquals(golden.melBins, filterBank.melBins)

        for (bin in 0 until golden.frequencyBins) {
            for (mel in 0 until golden.melBins) {
                val expectedWeight = expected[bin * golden.melBins + mel]
                val actualWeight = filterBank.weight(mel, bin)
                assertEquals(expectedWeight.toDouble(), actualWeight, "weight of bin $bin in filter $mel")
            }
        }
    }

    @TestFactory
    fun `matches liquidonnx log_mel`(): List<DynamicTest> = MediaGolden.manifest.logMel.map { case ->
        DynamicTest.dynamicTest(case.name) {
            val samples = MediaGolden.floats(case.samples)
            val expected = MediaGolden.floats(case.expected)
            assertEquals(case.sampleCount, samples.size)
            val frames = MediaGeometry.melFrameCount(samples.size)
            assertEquals(case.frames, frames)

            val actual = LogMelSpectrogram.compute(samples)

            assertEquals(Lfm3Omni.MEL_BINS * case.frames, actual.size)
            var worst = 0.0
            for (index in expected.indices) {
                worst = max(worst, abs(expected[index].toDouble() - actual[index]))
            }
            assertTrue(worst <= 1e-4, "${case.name}: largest difference $worst")
        }
    }

    @Test
    fun `silence sits at the log floor`() {
        val floor = (Lfm3Omni.MEL_LOG_FLOOR + Lfm3Omni.MEL_NORMALIZATION_OFFSET) / Lfm3Omni.MEL_NORMALIZATION_DIVISOR
        for (sampleCount in listOf(0, 1, 400, 1000)) {
            val features = LogMelSpectrogram.compute(FloatArray(sampleCount))
            val frames = MediaGeometry.melFrameCount(sampleCount)
            assertEquals(Lfm3Omni.MEL_BINS * frames, features.size)
            val allAtFloor = features.all { value -> value == floor.toFloat() }
            assertTrue(allAtFloor, "$sampleCount samples of silence")
        }
    }

    @Test
    fun `frames follow the padding rule`() {
        val expectedFrames = mapOf(0 to 1, 1 to 1, 400 to 1, 401 to 2, 560 to 2, 561 to 3, 16_000 to 99)
        for ((sampleCount, frames) in expectedFrames) {
            val features = LogMelSpectrogram.compute(FloatArray(sampleCount) { 0.1f })
            assertEquals(Lfm3Omni.MEL_BINS * frames, features.size, "$sampleCount samples")
        }
    }
}
