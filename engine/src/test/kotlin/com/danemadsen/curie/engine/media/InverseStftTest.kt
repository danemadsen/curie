package com.danemadsen.curie.engine.media

import com.danemadsen.curie.engine.Lfm3Omni
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InverseStftTest {
    @TestFactory
    fun `matches liquidonnx stft_to_wave`(): List<DynamicTest> = MediaGolden.manifest.inverseStft.map { case ->
        DynamicTest.dynamicTest(case.name) {
            val rows = MediaGolden.floats(case.rows)
            val expected = MediaGolden.floats(case.expected)

            val actual = decode(InverseStft(case.nFft, case.hop), rows, chunkRows = case.rowCount)

            assertEquals(case.rowCount * case.hop, actual.size)
            assertPeakRelativeError(expected, actual, tolerance = 1e-4, message = case.name)
        }
    }

    @Test
    fun `any chunking gives the samples of one push`() {
        val case = MediaGolden.manifest.inverseStft.maxBy { case -> case.rowCount }
        val rows = MediaGolden.floats(case.rows)
        val whole = decode(InverseStft(), rows, chunkRows = case.rowCount)

        for (chunkRows in listOf(1, 2, 3, 5, Lfm3Omni.CODEC_FRAME_STACK, 7, 22)) {
            val chunked = decode(InverseStft(), rows, chunkRows)
            assertContentEquals(whole, chunked, "chunks of $chunkRows rows")
        }

        val random = Random(4)
        repeat(5) {
            val chunked = decodeInRandomChunks(InverseStft(), rows, random)
            assertContentEquals(whole, chunked, "random chunks")
        }
    }

    @Test
    fun `an instance starts a new stream after finish`() {
        val case = MediaGolden.manifest.inverseStft.maxBy { case -> case.rowCount }
        val rows = MediaGolden.floats(case.rows)
        val stream = InverseStft()

        val first = decode(stream, rows, chunkRows = 4)
        val second = decode(stream, rows, chunkRows = 9)

        assertContentEquals(first, second)
    }

    @Test
    fun `empty pushes and finishes change nothing`() {
        val stream = InverseStft()
        val emptyPush = stream.push(FloatArray(0))
        val emptyFinish = stream.finish()
        assertEquals(0, emptyPush.size)
        assertEquals(0, emptyFinish.size)

        val case = MediaGolden.manifest.inverseStft.maxBy { case -> case.rowCount }
        val rows = MediaGolden.floats(case.rows)
        val expected = decode(InverseStft(), rows, chunkRows = case.rowCount)
        val head = stream.push(rows)
        val nothing = stream.push(FloatArray(0))
        val tail = stream.finish()

        assertEquals(0, nothing.size)
        assertContentEquals(expected, head + tail)
    }

    @Test
    fun `other sizes match a direct inverse DFT overlap-add`() {
        val random = Random(5)
        val configurations = listOf(16 to 4, 12 to 6, 30 to 10, 20 to 2)
        for ((nFft, hop) in configurations) {
            for (rowCount in listOf(1, 2, 3, 10)) {
                val rows = randomRows(random, nFft, rowCount)
                val expected = directIstft(rows, nFft, hop)

                for (chunkRows in 1..rowCount) {
                    val actual = decode(InverseStft(nFft, hop), rows, chunkRows)
                    assertEquals(rowCount * hop, actual.size, "nFft $nFft hop $hop, $rowCount rows")
                    assertPeakRelativeError(expected, actual, 1e-6, "nFft $nFft hop $hop, $rowCount rows by $chunkRows")
                }
            }
        }
    }

    @Test
    fun `rejects unusable sizes and partial rows`() {
        assertFailsWith<IllegalArgumentException> { InverseStft(nFft = 15, hop = 5) }
        assertFailsWith<IllegalArgumentException> { InverseStft(nFft = 16, hop = 16) }
        assertFailsWith<IllegalArgumentException> { InverseStft(nFft = 16, hop = 5) }
        assertFailsWith<IllegalArgumentException> { InverseStft(nFft = 14, hop = 4) }
        assertFailsWith<IllegalArgumentException> { InverseStft().push(FloatArray(1281)) }
    }

    private fun decode(stream: InverseStft, rows: FloatArray, chunkRows: Int): FloatArray {
        val rowLength = stream.nFft + 2
        val chunks = rows.toList().chunked(chunkRows * rowLength)
        var output = FloatArray(0)
        for (chunk in chunks) {
            output += stream.push(chunk.toFloatArray())
        }
        return output + stream.finish()
    }

    private fun decodeInRandomChunks(stream: InverseStft, rows: FloatArray, random: Random): FloatArray {
        val rowLength = stream.nFft + 2
        val rowCount = rows.size / rowLength
        var output = FloatArray(0)
        var row = 0
        while (row < rowCount) {
            val chunkRows = random.nextInt(0, 6).coerceAtMost(rowCount - row)
            val chunk = rows.copyOfRange(row * rowLength, (row + chunkRows) * rowLength)
            output += stream.push(chunk)
            row += chunkRows
        }
        return output + stream.finish()
    }

    private fun randomRows(random: Random, nFft: Int, rowCount: Int): FloatArray {
        val bins = nFft / 2 + 1
        val rows = FloatArray(rowCount * 2 * bins)
        for (row in 0 until rowCount) {
            for (bin in 0 until bins) {
                rows[row * 2 * bins + bin] = random.nextDouble(-3.0, 1.0).toFloat()
                rows[row * 2 * bins + bins + bin] = random.nextDouble(-PI, PI).toFloat()
            }
        }
        return rows
    }

    /**
     * istft_same_padding written out: each frame's inverse DFT by its definition (the imaginary
     * parts of the DC and Nyquist bins dropped), windowed, overlap-added and divided by the
     * overlap-added window², with (nFft - hop) / 2 samples trimmed at either end.
     */
    private fun directIstft(rows: FloatArray, nFft: Int, hop: Int): FloatArray {
        val bins = nFft / 2 + 1
        val rowCount = rows.size / (2 * bins)
        val window = DoubleArray(nFft) { index -> 0.5 - 0.5 * cos(2 * PI * index / nFft) }
        val size = (rowCount - 1) * hop + nFft
        val wave = DoubleArray(size)
        val envelope = DoubleArray(size)

        for (row in 0 until rowCount) {
            val offset = row * 2 * bins
            for (time in 0 until nFft) {
                var sample = 0.0
                for (bin in 0 until bins) {
                    val magnitude = exp(rows[offset + bin].toDouble())
                    val phase = rows[offset + bins + bin].toDouble()
                    val angle = 2 * PI * bin * time / nFft
                    val isEdge = bin == 0 || bin == bins - 1
                    // Bins 1 until nFft / 2 stand for themselves and their mirror images
                    val weight = if (isEdge) 1.0 else 2.0
                    val real = magnitude * cos(phase) * cos(angle)
                    val imaginary = if (isEdge) 0.0 else magnitude * sin(phase) * sin(angle)
                    sample += weight * (real - imaginary)
                }
                wave[row * hop + time] += sample / nFft * window[time]
                envelope[row * hop + time] += window[time] * window[time]
            }
        }

        val trim = (nFft - hop) / 2
        return FloatArray(size - 2 * trim) { index ->
            val position = trim + index
            (wave[position] / envelope[position]).toFloat()
        }
    }

    /** The largest difference, relative to the largest expected magnitude. */
    private fun assertPeakRelativeError(expected: FloatArray, actual: FloatArray, tolerance: Double, message: String) {
        assertEquals(expected.size, actual.size, "$message: length")
        var peak = 0.0
        var worst = 0.0
        for (index in expected.indices) {
            peak = max(peak, abs(expected[index].toDouble()))
            worst = max(worst, abs(expected[index].toDouble() - actual[index]))
        }
        assertTrue(worst <= tolerance * peak, "$message: largest error $worst against a peak of $peak")
    }
}
