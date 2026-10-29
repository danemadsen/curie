package com.danemadsen.curie.engine.media

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The discrete Fourier transform of [size] complex values, in place, for sizes whose only prime
 * factors are 2, 3 and 5 (the mel front end's 400 and the codec's 1280 among them).
 *
 * Mixed-radix decimation in time: the input is put in digit-reversed order, then each stage merges
 * sub-transforms with radix-4, 2, 3 or 5 butterflies. The permutation and the twiddle factors are
 * computed once; a transform allocates nothing and changes no state, so threads can share an
 * instance.
 *
 * The conventions are numpy.fft's: [forward] computes `X[k] = Σ x[n] e^(-2πi kn / size)` and
 * [inverse] computes `x[n] = (1 / size) Σ X[k] e^(2πi kn / size)`.
 */
class Fft(val size: Int) {
    /** A merge of [blockCount] blocks, each combining [radix] sub-transforms of [subLength] values. */
    private class Stage(val radix: Int, val subLength: Int, val blockCount: Int)

    /** In execution order, the shortest sub-transforms first (an array: iterating it allocates nothing). */
    private val stages: Array<Stage>

    /** The digit-reversal permutation as pairs of indices to exchange, in order. */
    private val swaps: IntArray

    /** cos(2πj / size): the real part of the forward twiddle factor j, cos - i sin. */
    private val twiddleCos: DoubleArray

    /** sin(2πj / size): the imaginary part of the forward twiddle factor j, negated. */
    private val twiddleSin: DoubleArray

    init {
        require(size > 0) { "an FFT needs at least one point, got $size" }
        val radices = factorize(size)
        stages = buildStages(radices)
        swaps = digitReversalSwaps(radices)
        twiddleCos = DoubleArray(size) { index -> cos(2 * PI * index / size) }
        twiddleSin = DoubleArray(size) { index -> sin(2 * PI * index / size) }
    }

    /** Replaces [re] + i [im] by its discrete Fourier transform. */
    fun forward(re: DoubleArray, im: DoubleArray) {
        checkLength(re, im)
        permute(re, im)
        for (stage in stages) {
            applyTwiddles(stage, re, im)
            applyButterflies(stage, re, im)
        }
    }

    /** Replaces [re] + i [im] by its inverse discrete Fourier transform, scaled by 1 / [size]. */
    fun inverse(re: DoubleArray, im: DoubleArray) {
        checkLength(re, im)

        // The inverse transform is conj(forward(conj(X))) / size
        for (index in 0 until size) {
            im[index] = -im[index]
        }
        forward(re, im)

        val scale = 1.0 / size
        for (index in 0 until size) {
            re[index] *= scale
            im[index] *= -scale
        }
    }

    private fun checkLength(re: DoubleArray, im: DoubleArray) {
        require(re.size == size && im.size == size) {
            "a $size-point FFT needs $size real and imaginary parts, got ${re.size} and ${im.size}"
        }
    }

    private fun permute(re: DoubleArray, im: DoubleArray) {
        for (pair in 0 until swaps.size / 2) {
            val first = swaps[2 * pair]
            val second = swaps[2 * pair + 1]

            val real = re[first]
            re[first] = re[second]
            re[second] = real

            val imaginary = im[first]
            im[first] = im[second]
            im[second] = imaginary
        }
    }

    /**
     * Multiplies value k of sub-transform q of each block by e^(-2πi qk / blockLength), which is
     * twiddle q * k * blockCount of the whole transform.
     */
    private fun applyTwiddles(stage: Stage, re: DoubleArray, im: DoubleArray) {
        val subLength = stage.subLength
        val blockLength = stage.radix * subLength
        for (block in 0 until stage.blockCount) {
            val blockStart = block * blockLength
            for (input in 1 until stage.radix) {
                val inputStart = blockStart + input * subLength
                val twiddleStep = input * stage.blockCount
                for (offset in 1 until subLength) {
                    val index = inputStart + offset
                    val twiddle = twiddleStep * offset
                    val cos = twiddleCos[twiddle]
                    val sin = twiddleSin[twiddle]
                    val real = re[index]
                    val imaginary = im[index]
                    re[index] = real * cos + imaginary * sin
                    im[index] = imaginary * cos - real * sin
                }
            }
        }
    }

    /** The radix-point transforms across the sub-transforms of each block. */
    private fun applyButterflies(stage: Stage, re: DoubleArray, im: DoubleArray) {
        val subLength = stage.subLength
        val blockLength = stage.radix * subLength
        for (block in 0 until stage.blockCount) {
            val blockStart = block * blockLength
            for (offset in 0 until subLength) {
                val first = blockStart + offset
                when (stage.radix) {
                    2 -> butterfly2(re, im, first, subLength)
                    3 -> butterfly3(re, im, first, subLength)
                    4 -> butterfly4(re, im, first, subLength)
                    5 -> butterfly5(re, im, first, subLength)
                    else -> error("no radix-${stage.radix} butterfly")
                }
            }
        }
    }

    private fun buildStages(radices: IntArray): Array<Stage> {
        // radices[0] splits the whole transform; the last radix merges single values
        val stages = ArrayList<Stage>(radices.size)
        var subLength = 1
        for (level in radices.indices.reversed()) {
            val radix = radices[level]
            val blockCount = size / (radix * subLength)
            stages += Stage(radix, subLength, blockCount)
            subLength *= radix
        }
        return stages.toTypedArray()
    }

    /**
     * The input order the stages expect: output position p takes the input whose mixed-radix
     * digits are p's in reverse. The permutation is returned as exchanges, cycle by cycle.
     */
    private fun digitReversalSwaps(radices: IntArray): IntArray {
        val source = IntArray(size) { position ->
            var remainder = position
            var length = size
            var input = 0
            var inputStride = 1
            for (radix in radices) {
                length /= radix
                val digit = remainder / length
                remainder %= length
                input += digit * inputStride
                inputStride *= radix
            }
            input
        }

        // Each exchange puts one value of a cycle in place: position p ends up with source[p]
        val swaps = ArrayList<Int>()
        val visited = BooleanArray(size)
        for (start in 0 until size) {
            var position = start
            while (!visited[position]) {
                visited[position] = true
                val next = source[position]
                if (next != start) {
                    swaps += position
                    swaps += next
                }
                position = next
            }
        }
        return swaps.toIntArray()
    }

    private companion object {
        val SIN_60 = sqrt(3.0) / 2
        val COS_72 = cos(2 * PI / 5)
        val SIN_72 = sin(2 * PI / 5)
        val COS_144 = cos(4 * PI / 5)
        val SIN_144 = sin(4 * PI / 5)

        /** The radices of [size], 4 first: the order of the stages from the whole transform down. */
        fun factorize(size: Int): IntArray {
            val radices = ArrayList<Int>()
            var remaining = size
            for (radix in intArrayOf(4, 2, 3, 5)) {
                while (remaining % radix == 0) {
                    radices += radix
                    remaining /= radix
                }
            }
            require(remaining == 1) { "FFT sizes must have no prime factors but 2, 3 and 5, got $size" }
            return radices.toIntArray()
        }

        fun butterfly2(re: DoubleArray, im: DoubleArray, first: Int, stride: Int) {
            val second = first + stride
            val re0 = re[first]
            val im0 = im[first]
            val re1 = re[second]
            val im1 = im[second]

            re[first] = re0 + re1
            im[first] = im0 + im1
            re[second] = re0 - re1
            im[second] = im0 - im1
        }

        fun butterfly3(re: DoubleArray, im: DoubleArray, first: Int, stride: Int) {
            val second = first + stride
            val third = second + stride
            val re0 = re[first]
            val im0 = im[first]
            val sumRe = re[second] + re[third]
            val sumIm = im[second] + im[third]
            val differenceRe = re[second] - re[third]
            val differenceIm = im[second] - im[third]

            // y1, y2 = x0 - (x1 + x2) / 2 ∓ i sin(60°) (x1 - x2)
            val middleRe = re0 - 0.5 * sumRe
            val middleIm = im0 - 0.5 * sumIm
            val rotatedRe = SIN_60 * differenceIm
            val rotatedIm = -SIN_60 * differenceRe

            re[first] = re0 + sumRe
            im[first] = im0 + sumIm
            re[second] = middleRe + rotatedRe
            im[second] = middleIm + rotatedIm
            re[third] = middleRe - rotatedRe
            im[third] = middleIm - rotatedIm
        }

        fun butterfly4(re: DoubleArray, im: DoubleArray, first: Int, stride: Int) {
            val second = first + stride
            val third = second + stride
            val fourth = third + stride
            val evenSumRe = re[first] + re[third]
            val evenSumIm = im[first] + im[third]
            val evenDifferenceRe = re[first] - re[third]
            val evenDifferenceIm = im[first] - im[third]
            val oddSumRe = re[second] + re[fourth]
            val oddSumIm = im[second] + im[fourth]
            val oddDifferenceRe = re[second] - re[fourth]
            val oddDifferenceIm = im[second] - im[fourth]

            // y1, y3 = (x0 - x2) ∓ i (x1 - x3)
            re[first] = evenSumRe + oddSumRe
            im[first] = evenSumIm + oddSumIm
            re[second] = evenDifferenceRe + oddDifferenceIm
            im[second] = evenDifferenceIm - oddDifferenceRe
            re[third] = evenSumRe - oddSumRe
            im[third] = evenSumIm - oddSumIm
            re[fourth] = evenDifferenceRe - oddDifferenceIm
            im[fourth] = evenDifferenceIm + oddDifferenceRe
        }

        fun butterfly5(re: DoubleArray, im: DoubleArray, first: Int, stride: Int) {
            val second = first + stride
            val third = second + stride
            val fourth = third + stride
            val fifth = fourth + stride
            val re0 = re[first]
            val im0 = im[first]
            val outerSumRe = re[second] + re[fifth]
            val outerSumIm = im[second] + im[fifth]
            val outerDifferenceRe = re[second] - re[fifth]
            val outerDifferenceIm = im[second] - im[fifth]
            val innerSumRe = re[third] + re[fourth]
            val innerSumIm = im[third] + im[fourth]
            val innerDifferenceRe = re[third] - re[fourth]
            val innerDifferenceIm = im[third] - im[fourth]

            // y1, y4 = x0 + cos72 (x1 + x4) + cos144 (x2 + x3) ∓ i (sin72 (x1 - x4) + sin144 (x2 - x3))
            val nearRe = re0 + COS_72 * outerSumRe + COS_144 * innerSumRe
            val nearIm = im0 + COS_72 * outerSumIm + COS_144 * innerSumIm
            val nearSineRe = SIN_72 * outerDifferenceRe + SIN_144 * innerDifferenceRe
            val nearSineIm = SIN_72 * outerDifferenceIm + SIN_144 * innerDifferenceIm

            // y2, y3 = x0 + cos144 (x1 + x4) + cos72 (x2 + x3) ∓ i (sin144 (x1 - x4) - sin72 (x2 - x3))
            val farRe = re0 + COS_144 * outerSumRe + COS_72 * innerSumRe
            val farIm = im0 + COS_144 * outerSumIm + COS_72 * innerSumIm
            val farSineRe = SIN_144 * outerDifferenceRe - SIN_72 * innerDifferenceRe
            val farSineIm = SIN_144 * outerDifferenceIm - SIN_72 * innerDifferenceIm

            // -i (a + ib) = b - ia
            re[first] = re0 + outerSumRe + innerSumRe
            im[first] = im0 + outerSumIm + innerSumIm
            re[second] = nearRe + nearSineIm
            im[second] = nearIm - nearSineRe
            re[fifth] = nearRe - nearSineIm
            im[fifth] = nearIm + nearSineRe
            re[third] = farRe + farSineIm
            im[third] = farIm - farSineRe
            re[fourth] = farRe - farSineIm
            im[fourth] = farIm + farSineRe
        }
    }
}
