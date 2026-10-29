package com.danemadsen.curie.engine.media

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ImageResizerTest {
    /**
     * The golden data is Pillow 12.3 resizing horizontally and then vertically: Pillow's arithmetic
     * in the pass order of the processors' torchvision resize, as ImageResizer computes it, so every
     * pixel must match. The 5 x 600 source checks the order: a single Image.resize call would run
     * Pillow's vertical pass first there.
     */
    @TestFactory
    fun `matches Pillow bicubic resampling run horizontally first`(): List<DynamicTest> =
        MediaGolden.manifest.resize.map { case ->
            DynamicTest.dynamicTest(case.name) {
                val source = MediaGolden.image(case.source, case.sourceWidth, case.sourceHeight)
                val expected = MediaGolden.bytes(case.expected)

                val resized = ImageResizer.resize(source, case.width, case.height)

                assertEquals(case.width, resized.width)
                assertEquals(case.height, resized.height)
                var largest = 0
                var total = 0L
                for (index in expected.indices) {
                    val difference = abs(level(expected[index]) - level(resized.pixels[index]))
                    largest = max(largest, difference)
                    total += difference
                }
                val mean = total.toDouble() / expected.size
                assertEquals(0, largest, "${case.name}: largest difference $largest, mean $mean")
            }
        }

    @Test
    fun `keeps an image of the target size`() {
        val image = RgbImage(4, 3, ByteArray(4 * 3 * 3) { index -> index.toByte() })
        val resized = ImageResizer.resize(image, 4, 3)
        assertSame(image, resized)
    }

    @Test
    fun `keeps a uniform colour uniform`() {
        val colour = byteArrayOf(200.toByte(), 13, 255.toByte())
        val image = RgbImage(37, 23, ByteArray(37 * 23 * 3) { index -> colour[index % 3] })
        for ((width, height) in listOf(5 to 3, 64 to 32, 111 to 7, 1 to 1)) {
            val resized = ImageResizer.resize(image, width, height)
            val uniform = resized.pixels.withIndex().all { (index, value) -> value == colour[index % 3] }
            assertTrue(uniform, "${width}x$height")
        }
    }

    @Test
    fun `rejects an empty target`() {
        val image = RgbImage(2, 2, ByteArray(12))
        assertFailsWith<IllegalArgumentException> { ImageResizer.resize(image, 0, 2) }
        assertFailsWith<IllegalArgumentException> { ImageResizer.resize(image, 2, -1) }
    }

    private fun level(value: Byte): Int = value.toInt() and 0xFF
}
