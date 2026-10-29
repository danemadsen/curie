package com.danemadsen.curie.engine.media

import com.danemadsen.curie.engine.Lfm3Omni
import kotlin.math.min

/**
 * One vision encoder input: the pixel_values of an image or of a temporal pair of video frames,
 * whose [grid] holds rows x columns patches of 16 x 16 pixels.
 *
 * [pixelValues] has the layout `[rows / 2, columns / 2, 2, 2, PATCH_DIM]` ([shape]): blocks of 2 x 2
 * patches row by row, the patches of a block row by row, and each patch as `[channel][temporal][y][x]`
 * with 3 channels, 2 temporal slots and 16 x 16 pixels ([Lfm3Omni.PATCH_DIM] values).
 */
class VisionPatches(val grid: MediaGeometry.Grid, val pixelValues: FloatArray) {
    init {
        require(grid.rows > 0 && grid.columns > 0) { "the patch grid must not be empty: $grid" }
        require(grid.rows % Lfm3Omni.MERGE_SIZE == 0 && grid.columns % Lfm3Omni.MERGE_SIZE == 0) {
            "the patch grid must have even sides: $grid"
        }
        val expectedSize = grid.rows * grid.columns * Lfm3Omni.PATCH_DIM
        require(pixelValues.size == expectedSize) {
            "a $grid grid needs $expectedSize pixel values, got ${pixelValues.size}"
        }
    }

    /** The vision encoder's pixel_values shape. */
    val shape: LongArray
        get() = longArrayOf(
            (grid.rows / Lfm3Omni.MERGE_SIZE).toLong(),
            (grid.columns / Lfm3Omni.MERGE_SIZE).toLong(),
            Lfm3Omni.MERGE_SIZE.toLong(),
            Lfm3Omni.MERGE_SIZE.toLong(),
            Lfm3Omni.PATCH_DIM.toLong(),
        )
}

/**
 * The image and video preprocessing of Lfm3VlImageProcessor and Lfm3VlVideoProcessor: the
 * antialiased bicubic resize to the smart_resize target ([ImageResizer]), the fused rescale and
 * normalisation (v - 127.5) / 127.5 in float32, and patchify, laid out per temporal patch as
 * liquidonnx's run_vision feeds the vision encoder.
 */
object VisionPreprocessor {
    private const val PATCH_SIZE = Lfm3Omni.PATCH_SIZE
    private const val MERGE_SIZE = Lfm3Omni.MERGE_SIZE
    private const val CHANNELS = 3


    /** The normalised value of each 8-bit level. */
    private val NORMALIZED_LEVELS = FloatArray(256) { level -> (level - Lfm3Omni.PIXEL_CENTER) / Lfm3Omni.PIXEL_CENTER }

    /** A still image, resized to MediaGeometry.imageResizeTarget; it fills both temporal slots. */
    fun image(image: RgbImage, maxTokens: Int = MediaLimits.MAX_IMAGE_TOKENS): VisionPatches {
        val target = MediaGeometry.imageResizeTarget(image.width, image.height, maxTokens)
        val resized = ImageResizer.resize(image, target.width, target.height)
        return patchify(resized, resized)
    }

    /**
     * The sampled [frames] of a video (all of one size), resized to [target]
     * (MediaGeometry.videoResizeTarget): one input per temporal group of frames 2g and 2g + 1,
     * where an odd frame count repeats the last frame.
     */
    fun video(frames: List<RgbImage>, target: MediaGeometry.Size): List<VisionPatches> {
        require(frames.isNotEmpty()) { "a video needs frames" }
        val firstFrame = frames.first()
        val sameSize = frames.all { frame -> frame.width == firstFrame.width && frame.height == firstFrame.height }
        require(sameSize) { "the frames of a video must all have one size" }

        val resized = frames.map { frame -> ImageResizer.resize(frame, target.width, target.height) }
        val groupCount = MediaGeometry.videoGroupCount(resized.size)
        return List(groupCount) { group ->
            val first = resized[group * Lfm3Omni.TEMPORAL_PATCH_SIZE]
            val second = resized[min(group * Lfm3Omni.TEMPORAL_PATCH_SIZE + 1, resized.lastIndex)]
            patchify(first, second)
        }
    }

    /** The encoder input of two already resized frames: [first] in temporal slot 0, [second] in slot 1. */
    internal fun patchify(first: RgbImage, second: RgbImage): VisionPatches {
        require(first.width == second.width && first.height == second.height) {
            "the frames of a temporal patch must have one size: ${first.width}x${first.height} and " +
                "${second.width}x${second.height}"
        }
        require(first.width % Lfm3Omni.TOKEN_PIXELS == 0 && first.height % Lfm3Omni.TOKEN_PIXELS == 0) {
            "the frame sides must be multiples of ${Lfm3Omni.TOKEN_PIXELS}: ${first.width}x${first.height}"
        }

        val grid = MediaGeometry.Grid(rows = first.height / PATCH_SIZE, columns = first.width / PATCH_SIZE)
        val pixelValues = FloatArray(grid.rows * grid.columns * Lfm3Omni.PATCH_DIM)
        writeTemporalSlot(first, 0, grid, pixelValues)
        writeTemporalSlot(second, 1, grid, pixelValues)
        return VisionPatches(grid, pixelValues)
    }

    private fun writeTemporalSlot(frame: RgbImage, temporal: Int, grid: MediaGeometry.Grid, pixelValues: FloatArray) {
        val blockColumns = grid.columns / MERGE_SIZE
        for (y in 0 until frame.height) {
            val patchRow = y / PATCH_SIZE
            val rowInPatch = y % PATCH_SIZE
            for (x in 0 until frame.width) {
                val patchColumn = x / PATCH_SIZE
                val columnInPatch = x % PATCH_SIZE

                // Patches go block by block (2 x 2 patches each), then row by row inside a block
                val block = (patchRow / MERGE_SIZE) * blockColumns + patchColumn / MERGE_SIZE
                val patchInBlock = (patchRow % MERGE_SIZE) * MERGE_SIZE + patchColumn % MERGE_SIZE
                val patch = block * MERGE_SIZE * MERGE_SIZE + patchInBlock
                val pixel = (y * frame.width + x) * CHANNELS

                for (channel in 0 until CHANNELS) {
                    val plane = channel * Lfm3Omni.TEMPORAL_PATCH_SIZE + temporal
                    val offset = (plane * PATCH_SIZE + rowInPatch) * PATCH_SIZE + columnInPatch
                    val level = frame.pixels[pixel + channel].toInt() and 0xFF
                    pixelValues[patch * Lfm3Omni.PATCH_DIM + offset] = NORMALIZED_LEVELS[level]
                }
            }
        }
    }
}
