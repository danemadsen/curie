package com.danemadsen.curie.engine.media

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Antialiased bicubic resizing of 8-bit RGB images, as the image and video processors do it
 * (torchvision's resize of uint8 tensors with BICUBIC and antialias = True, which follows Pillow):
 *
 *  - Keys' cubic convolution kernel with a = -0.5, its support of 2 pixels widened by the scale
 *    factor when shrinking, centred on each output pixel's centre mapped into the input;
 *  - the weights of each output pixel normalised to sum to 1, then quantised to 22 fractional bits;
 *  - a horizontal pass, then a vertical pass through an 8-bit intermediate image, each rounding
 *    half up and clamping to 0..255.
 *
 * That is Pillow's arithmetic in torchvision's pass order: the result equals Pillow 12.3 resizing
 * horizontally and then vertically, bit for bit. A single Image.resize(BICUBIC) call of Pillow 12.3
 * gives the same pixels unless the image is more than 100 times taller than wide and shrinks
 * vertically: Pillow then runs the vertical pass first, which moves pixels near hard edges by
 * several levels. torchvision rounds the weights to 16-bit integers instead, which moves isolated
 * pixels by one level, rarely two.
 */
object ImageResizer {
    private const val PRECISION_BITS = 32 - 8 - 2
    private const val FIXED_POINT_ONE = (1 shl PRECISION_BITS).toDouble()
    private const val ROUNDING = 1 shl (PRECISION_BITS - 1)
    private const val CUBIC_A = -0.5
    private const val CUBIC_SUPPORT = 2.0
    private const val CHANNELS = 3

    /**
     * For each output pixel i along one axis: the input pixels `first[i] until first[i] + count[i]`
     * contribute, with the fixed-point weights from `weights[i * stride]` on.
     */
    private class AxisWeights(val first: IntArray, val count: IntArray, val weights: IntArray, val stride: Int)

    /** [image] resized to [width] x [height]; the image itself when it already has that size. */
    fun resize(image: RgbImage, width: Int, height: Int): RgbImage {
        require(width > 0 && height > 0) { "the target size must not be empty: ${width}x$height" }
        if (width == image.width && height == image.height) {
            return image
        }

        val resizedRows = if (width != image.width) resizeHorizontally(image, width) else image
        return if (height != image.height) resizeVertically(resizedRows, height) else resizedRows
    }

    private fun resizeHorizontally(image: RgbImage, width: Int): RgbImage {
        val axis = axisWeights(image.width, width)
        val input = image.pixels
        val output = ByteArray(width * image.height * CHANNELS)
        for (y in 0 until image.height) {
            val rowStart = y * image.width
            for (x in 0 until width) {
                var red = ROUNDING
                var green = ROUNDING
                var blue = ROUNDING
                val weightStart = x * axis.stride
                for (tap in 0 until axis.count[x]) {
                    val weight = axis.weights[weightStart + tap]
                    val pixel = (rowStart + axis.first[x] + tap) * CHANNELS
                    red += unsigned(input[pixel]) * weight
                    green += unsigned(input[pixel + 1]) * weight
                    blue += unsigned(input[pixel + 2]) * weight
                }
                val target = (y * width + x) * CHANNELS
                output[target] = clip(red)
                output[target + 1] = clip(green)
                output[target + 2] = clip(blue)
            }
        }
        return RgbImage(width, image.height, output)
    }

    private fun resizeVertically(image: RgbImage, height: Int): RgbImage {
        val axis = axisWeights(image.height, height)
        val input = image.pixels
        val output = ByteArray(image.width * height * CHANNELS)
        for (y in 0 until height) {
            val weightStart = y * axis.stride
            for (x in 0 until image.width) {
                var red = ROUNDING
                var green = ROUNDING
                var blue = ROUNDING
                for (tap in 0 until axis.count[y]) {
                    val weight = axis.weights[weightStart + tap]
                    val pixel = ((axis.first[y] + tap) * image.width + x) * CHANNELS
                    red += unsigned(input[pixel]) * weight
                    green += unsigned(input[pixel + 1]) * weight
                    blue += unsigned(input[pixel + 2]) * weight
                }
                val target = (y * image.width + x) * CHANNELS
                output[target] = clip(red)
                output[target + 1] = clip(green)
                output[target + 2] = clip(blue)
            }
        }
        return RgbImage(image.width, height, output)
    }

    /** Pillow's precompute_coeffs and normalize_coeffs_8bpc for [inputSize] to [outputSize] pixels. */
    private fun axisWeights(inputSize: Int, outputSize: Int): AxisWeights {
        val scale = inputSize.toDouble() / outputSize
        val filterScale = max(scale, 1.0)
        val inverseFilterScale = 1.0 / filterScale
        val support = CUBIC_SUPPORT * filterScale
        val stride = ceil(support).toInt() * 2 + 1

        val first = IntArray(outputSize)
        val count = IntArray(outputSize)
        val weights = IntArray(outputSize * stride)
        val taps = DoubleArray(stride)
        for (output in 0 until outputSize) {
            // The output pixel's centre in input coordinates; the bounds round to the nearest pixel
            val center = (output + 0.5) * scale
            val start = max((center - support + 0.5).toInt(), 0)
            val end = min((center + support + 0.5).toInt(), inputSize)
            val tapCount = end - start

            var total = 0.0
            for (tap in 0 until tapCount) {
                // From the output pixel's centre to the input pixel's, in the kernel's units
                val distance = (tap + start - center + 0.5) * inverseFilterScale
                val weight = cubic(distance)
                taps[tap] = weight
                total += weight
            }
            for (tap in 0 until tapCount) {
                val normalized = if (total != 0.0) taps[tap] / total else taps[tap]
                weights[output * stride + tap] = toFixedPoint(normalized)
            }
            first[output] = start
            count[output] = tapCount
        }
        return AxisWeights(first, count, weights, stride)
    }

    /** Keys' cubic convolution kernel with a = -0.5 (Catmull-Rom). */
    private fun cubic(x: Double): Double {
        val distance = abs(x)
        return when {
            distance < 1.0 -> ((CUBIC_A + 2.0) * distance - (CUBIC_A + 3.0)) * distance * distance + 1
            distance < 2.0 -> (((distance - 5) * distance + 8) * distance - 4) * CUBIC_A
            else -> 0.0
        }
    }

    /** Rounds half away from zero, as Pillow's (int)(±0.5 + weight * 2^22) does. */
    private fun toFixedPoint(weight: Double): Int {
        val scaled = weight * FIXED_POINT_ONE
        return if (weight < 0) (scaled - 0.5).toInt() else (scaled + 0.5).toInt()
    }

    private fun unsigned(value: Byte): Int = value.toInt() and 0xFF

    /** Drops the fraction bits (the sum started at one half, so this rounds half up) and clamps. */
    private fun clip(sum: Int): Byte {
        val value = sum shr PRECISION_BITS
        return value.coerceIn(0, 255).toByte()
    }
}
