package com.danemadsen.curie.engine.media

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

/**
 * transformers.audio_utils.mel_filter_bank with norm = "slaney" and mel_scale = "slaney", ported
 * operation for operation in double: [melBins] triangular filters over [frequencyBins] FFT bins
 * from 0 to [sampleRate] / 2, their corners evenly spaced on the Slaney mel scale between
 * [minFrequency] and [maxFrequency], each divided by its width in Hz. The weights are rounded to
 * float32, as liquidonnx's mel_filters stores them.
 */
internal class MelFilterBank(
    val frequencyBins: Int,
    val melBins: Int,
    minFrequency: Double,
    maxFrequency: Double,
    sampleRate: Int,
) {
    /** `weights[mel][bin]`: the float32 weight of FFT bin `bin` in mel filter `mel`. */
    private val weights: Array<DoubleArray>

    /** The first FFT bin of each filter's non-zero weights: filter m spans `firstBin[m] until endBin[m]`. */
    private val firstBin: IntArray

    /** The FFT bin after the last non-zero weight of each filter. */
    private val endBin: IntArray

    init {
        require(frequencyBins >= 2) { "a filter bank needs at least 2 frequency bins, got $frequencyBins" }
        require(melBins > 0) { "a filter bank needs at least one mel filter, got $melBins" }
        require(minFrequency <= maxFrequency) { "minFrequency $minFrequency exceeds maxFrequency $maxFrequency" }

        // The filter corners: melBins + 2 points evenly spaced in mel, in Hz
        val lowestMel = hertzToMel(minFrequency)
        val highestMel = hertzToMel(maxFrequency)
        val melFrequencies = linspace(lowestMel, highestMel, melBins + 2)
        val filterFrequencies = DoubleArray(melBins + 2) { index -> melToHertz(melFrequencies[index]) }
        val filterWidths = DoubleArray(melBins + 1) { index ->
            filterFrequencies[index + 1] - filterFrequencies[index]
        }
        val fftFrequencies = linspace(0.0, (sampleRate / 2).toDouble(), frequencyBins)

        weights = Array(melBins) { mel ->
            // Slaney's area normalization: about constant energy per channel
            val areaNormalization = 2.0 / (filterFrequencies[mel + 2] - filterFrequencies[mel])
            DoubleArray(frequencyBins) { bin ->
                val rising = -(filterFrequencies[mel] - fftFrequencies[bin]) / filterWidths[mel]
                val falling = (filterFrequencies[mel + 2] - fftFrequencies[bin]) / filterWidths[mel + 1]
                val triangle = max(0.0, min(rising, falling))
                val weight = triangle * areaNormalization
                weight.toFloat().toDouble()
            }
        }
        firstBin = IntArray(melBins) { mel -> nonZeroStart(weights[mel]) }
        endBin = IntArray(melBins) { mel -> nonZeroEnd(weights[mel]) }
    }

    /** The float32 weight of FFT bin [bin] in filter [mel]. */
    fun weight(mel: Int, bin: Int): Double = weights[mel][bin]

    /** The energy of [power] (one value per FFT bin) in filter [mel]. */
    fun energy(mel: Int, power: DoubleArray): Double {
        val filter = weights[mel]
        var sum = 0.0
        for (bin in firstBin[mel] until endBin[mel]) {
            sum += filter[bin] * power[bin]
        }
        return sum
    }

    private companion object {
        const val MIN_LOG_HERTZ = 1000.0
        const val MIN_LOG_MEL = 15.0
        val MELS_PER_LOG_HERTZ = 27.0 / ln(6.4)
        val LOG_HERTZ_PER_MEL = ln(6.4) / 27.0

        /** Slaney's mel scale: linear below 1 kHz, logarithmic above. */
        fun hertzToMel(frequency: Double): Double {
            if (frequency >= MIN_LOG_HERTZ) {
                return MIN_LOG_MEL + ln(frequency / MIN_LOG_HERTZ) * MELS_PER_LOG_HERTZ
            }
            return 3.0 * frequency / 200.0
        }

        fun melToHertz(mel: Double): Double {
            if (mel >= MIN_LOG_MEL) {
                return MIN_LOG_HERTZ * exp(LOG_HERTZ_PER_MEL * (mel - MIN_LOG_MEL))
            }
            return 200.0 * mel / 3.0
        }

        /** numpy.linspace(start, stop, count): start + i * step, with the last value exactly stop. */
        fun linspace(start: Double, stop: Double, count: Int): DoubleArray {
            val step = (stop - start) / (count - 1)
            return DoubleArray(count) { index ->
                if (index == count - 1) stop else index * step + start
            }
        }

        fun nonZeroStart(filter: DoubleArray): Int {
            val first = filter.indexOfFirst { weight -> weight != 0.0 }
            return if (first < 0) 0 else first
        }

        fun nonZeroEnd(filter: DoubleArray): Int = filter.indexOfLast { weight -> weight != 0.0 } + 1
    }
}
