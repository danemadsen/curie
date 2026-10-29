package com.danemadsen.curie.engine.media

import com.danemadsen.curie.engine.Lfm3Omni
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class VisionPreprocessorTest {
    @TestFactory
    fun `images match the image processor`(): List<DynamicTest> = MediaGolden.manifest.imagePatches.map { case ->
        DynamicTest.dynamicTest(case.name) {
            val image = MediaGolden.image(case.source, case.sourceWidth, case.sourceHeight)
            val expected = MediaGolden.floats(case.expected)

            // Without a token budget the image is at a resize target already: patchify alone
            val patches = if (case.maxTokens == null) {
                VisionPreprocessor.patchify(image, image)
            } else {
                VisionPreprocessor.image(image, case.maxTokens)
            }

            assertEquals(MediaGeometry.Grid(case.rows, case.columns), patches.grid)
            assertContentEquals(expected, patches.pixelValues)
        }
    }

    @TestFactory
    fun `videos match the video processor`(): List<DynamicTest> = MediaGolden.manifest.videoPatches.map { case ->
        DynamicTest.dynamicTest(case.name) {
            val frames = case.frames.map { path -> MediaGolden.image(path, case.frameWidth, case.frameHeight) }
            val target = MediaGeometry.Size(case.targetWidth, case.targetHeight)

            val groups = VisionPreprocessor.video(frames, target)

            assertEquals(case.expected.size, groups.size)
            for ((index, group) in groups.withIndex()) {
                val expected = MediaGolden.floats(case.expected[index])
                assertEquals(MediaGeometry.Grid(case.rows, case.columns), group.grid)
                assertContentEquals(expected, group.pixelValues, "group $index")
            }
        }
    }

    @Test
    fun `image grids are the prompt builder's`() {
        val sizes = listOf(640 to 480, 1920 to 1080, 300 to 300, 50 to 2000, 33 to 17)
        for ((width, height) in sizes) {
            val image = RgbImage(width, height, ByteArray(width * height * 3))
            for (maxTokens in listOf(MediaLimits.MAX_IMAGE_TOKENS, 64)) {
                val patches = VisionPreprocessor.image(image, maxTokens)
                val promptGrid = MediaGeometry.imageGrid(width, height, maxTokens)
                assertEquals(promptGrid, patches.grid, "${width}x$height, $maxTokens tokens")
            }
        }
    }

    @Test
    fun `normalises levels as the fused torchvision rescale and normalise`() {
        val pixels = ByteArray(32 * 32 * 3) { index -> (index % 256).toByte() }
        val image = RgbImage(32, 32, pixels)

        val patches = VisionPreprocessor.patchify(image, image)

        // Pixel (0, 0) of the first patch: channel planes are 16 x 16, temporal slots duplicated
        val planeSize = Lfm3Omni.PATCH_SIZE * Lfm3Omni.PATCH_SIZE
        for (channel in 0 until 3) {
            val expected = (channel - 127.5f) / 127.5f
            assertEquals(expected, patches.pixelValues[(channel * 2) * planeSize])
            assertEquals(expected, patches.pixelValues[(channel * 2 + 1) * planeSize])
        }
    }

    @Test
    fun `patches report the encoder input shape`() {
        val patches = VisionPatches(MediaGeometry.Grid(4, 6), FloatArray(4 * 6 * Lfm3Omni.PATCH_DIM))
        assertContentEquals(longArrayOf(2, 3, 2, 2, Lfm3Omni.PATCH_DIM.toLong()), patches.shape)
    }

    @Test
    fun `rejects inconsistent input`() {
        assertFailsWith<IllegalArgumentException> { VisionPatches(MediaGeometry.Grid(2, 2), FloatArray(10)) }
        assertFailsWith<IllegalArgumentException> {
            VisionPatches(MediaGeometry.Grid(3, 2), FloatArray(3 * 2 * Lfm3Omni.PATCH_DIM))
        }

        val small = RgbImage(32, 32, ByteArray(32 * 32 * 3))
        val large = RgbImage(64, 32, ByteArray(64 * 32 * 3))
        val target = MediaGeometry.Size(32, 32)
        val unpatchable = MediaGeometry.Size(48, 32)
        assertFailsWith<IllegalArgumentException> { VisionPreprocessor.video(emptyList(), target) }
        assertFailsWith<IllegalArgumentException> { VisionPreprocessor.video(listOf(small, large), target) }
        assertFailsWith<IllegalArgumentException> { VisionPreprocessor.video(listOf(small), unpatchable) }
        assertFailsWith<IllegalArgumentException> { VisionPreprocessor.patchify(small, large) }
    }
}
