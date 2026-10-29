package com.danemadsen.curie.engine.media

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * Channel mixing and sample-rate conversion of decoded audio, for the 16 kHz mono input of the
 * audio encoder.
 *
 * [resample] is band-limited interpolation (J. O. Smith's method) with a Kaiser-windowed sinc that
 * cuts off at 96% of the lower of the two Nyquist frequencies and spans 64 of its periods on each
 * side. Up to 92% of that Nyquist frequency the gain stays within 1e-4 (0.001 dB) of one, a
 * passband about as wide as that of soxr's HQ preset (librosa's default resampler). From the
 * Nyquist frequency up, where aliases come from, it stays about 80 dB down or lower. The kernel is
 * tabulated once and interpolated linearly, so every pair of rates takes the same memory, and the
 * arithmetic is deterministic.
 */
object Resampler {
    /**
     * Kernel half-width, in periods of the lower of the two sample rates. With [KAISER_BETA] it sets
     * the width of the transition band: 8% of the Nyquist frequency.
     */
    private const val ZERO_CROSSINGS = 64

    /** Kernel table entries per zero crossing: linear interpolation between them errs by about 1e-6. */
    private const val TABLE_RESOLUTION = 512

    /** 80 dB of stopband attenuation: beta = 0.1102 (80 - 8.7). */
    private const val KAISER_BETA = 7.86

    /**
     * Cutoff (the -6 dB point) as a fraction of the lower Nyquist frequency, centred in the
     * transition band so that the band ends at the Nyquist frequency.
     */
    private const val CUTOFF = 0.96

    /**
     * The kernel CUTOFF sinc(CUTOFF u) Kaiser(u / ZERO_CROSSINGS) at u = i / TABLE_RESOLUTION, for
     * u from 0 to ZERO_CROSSINGS, plus a zero guard entry for the interpolation.
     */
    private val kernel = DoubleArray(ZERO_CROSSINGS * TABLE_RESOLUTION + 2) { index ->
        val distance = index.toDouble() / TABLE_RESOLUTION
        if (distance > ZERO_CROSSINGS) {
            0.0
        } else {
            val window = SignalWindows.kaiser(distance / ZERO_CROSSINGS, KAISER_BETA)
            CUTOFF * sinc(CUTOFF * distance) * window
        }
    }

    /** Averages the channels of [interleaved] frames (channel samples side by side) into one. */
    fun toMono(interleaved: FloatArray, channels: Int): FloatArray {
        require(channels > 0) { "audio needs at least one channel, got $channels" }
        require(interleaved.size % channels == 0) {
            "${interleaved.size} samples are not whole frames of $channels channels"
        }
        if (channels == 1) {
            return interleaved.copyOf()
        }

        val frameCount = interleaved.size / channels
        return FloatArray(frameCount) { frame ->
            var sum = 0.0
            for (channel in 0 until channels) {
                sum += interleaved[frame * channels + channel]
            }
            (sum / channels).toFloat()
        }
    }

    /**
     * [samples] at [fromRate] converted to [toRate]: [outputLength] samples, output sample j at the
     * time of input sample j * fromRate / toRate (the first samples coincide, as in
     * scipy.signal.resample_poly), with silence assumed around the input.
     */
    fun resample(samples: FloatArray, fromRate: Int, toRate: Int): FloatArray {
        require(fromRate > 0 && toRate > 0) { "sample rates must be positive: $fromRate to $toRate" }
        if (fromRate == toRate) {
            return samples.copyOf()
        }

        // Kernel distances are in periods of the lower rate: one input period is `scale` of them
        val scale = min(1.0, toRate.toDouble() / fromRate)
        val length = outputLength(samples.size, fromRate, toRate)
        return FloatArray(length) { index ->
            // The output's time is input sample `before` plus `phase` of a period, exactly
            val position = index.toLong() * fromRate
            val before = position / toRate
            val phase = (position % toRate).toDouble() / toRate
            val value = interpolate(samples, before.toInt(), phase, scale)
            value.toFloat()
        }
    }

    /** ceil(sampleCount * toRate / fromRate), the length scipy.signal.resample_poly gives. */
    fun outputLength(sampleCount: Int, fromRate: Int, toRate: Int): Int {
        require(sampleCount >= 0) { "sample counts cannot be negative, got $sampleCount" }
        require(fromRate > 0 && toRate > 0) { "sample rates must be positive: $fromRate to $toRate" }
        val length = (sampleCount.toLong() * toRate + fromRate - 1) / fromRate
        require(length <= Int.MAX_VALUE) { "$sampleCount samples at $toRate / $fromRate are too many" }
        return length.toInt()
    }

    /** The band-limited signal [phase] input periods after sample [before]. */
    private fun interpolate(samples: FloatArray, before: Int, phase: Double, scale: Double): Double {
        var sum = 0.0

        // Input sample `before` and the ones before it: phase, phase + 1, ... input periods away
        var step = 0
        while (before - step >= 0) {
            val distance = (phase + step) * scale
            if (distance >= ZERO_CROSSINGS) {
                break
            }
            sum += samples[before - step] * kernelAt(distance)
            step++
        }

        // The input samples after it: 1 - phase, 2 - phase, ... input periods away
        step = 1
        while (before + step < samples.size) {
            val distance = (step - phase) * scale
            if (distance >= ZERO_CROSSINGS) {
                break
            }
            sum += samples[before + step] * kernelAt(distance)
            step++
        }

        // Stretching the kernel in time to filter below the output's Nyquist frequency scales its gain
        return sum * scale
    }

    private fun kernelAt(distance: Double): Double {
        val position = distance * TABLE_RESOLUTION
        val index = position.toInt()
        val fraction = position - index
        return kernel[index] + fraction * (kernel[index + 1] - kernel[index])
    }

    private fun sinc(x: Double): Double {
        if (x == 0.0) {
            return 1.0
        }
        val angle = PI * x
        return sin(angle) / angle
    }
}
