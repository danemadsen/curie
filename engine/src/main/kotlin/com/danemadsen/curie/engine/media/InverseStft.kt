package com.danemadsen.curie.engine.media

import com.danemadsen.curie.engine.Lfm3Omni
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sin

/**
 * The inverse STFT behind the codec decoder, streamed: rows of stft_features in, waveform samples
 * out. Each row holds the log-magnitudes of bins 0..[nFft] / 2, then their phases.
 *
 * A row's spectrum exp(log-magnitude) e^(i phase) goes through numpy's irfft (the imaginary parts
 * of the DC and Nyquist bins ignored, scaled by 1 / [nFft]) and the periodic Hann window; the
 * frames overlap-add every [hop] samples and are divided by the overlap-added window², and
 * ([nFft] - [hop]) / 2 samples are dropped at the start and the end of the stream. Concatenated,
 * the outputs of [push] and [finish] are liquidonnx's istft_same_padding of all the rows, rows *
 * [hop] samples, however the rows are split into pushes. Like the codec's streaming decode
 * (modeling_liquid_codec.py), the last [nFft] - [hop] samples wait for the next rows.
 *
 * An instance holds the state of one stream: use it from one thread at a time.
 */
class InverseStft(
    val nFft: Int = Lfm3Omni.CODEC_N_FFT,
    val hop: Int = Lfm3Omni.CODEC_HOP_LENGTH,
) {
    init {
        require(nFft >= 2 && nFft % 2 == 0) { "nFft must be even, got $nFft" }
        require(hop in 1 until nFft) { "hop must be in 1 until $nFft, got $hop" }
        require((nFft - hop) % 2 == 0) { "nFft - hop must be even to trim both ends equally, got ${nFft - hop}" }
    }

    private val bins = nFft / 2 + 1

    /** Floats per row: the log-magnitudes, then the phases. */
    private val rowLength = 2 * bins

    /** Samples that the next rows still add to. */
    private val overlap = nFft - hop

    /** Samples dropped at either end of a stream ("same" padding). */
    private val trim = overlap / 2

    private val window = SignalWindows.periodicHann(nFft)
    private val windowSquared = DoubleArray(nFft) { index -> window[index] * window[index] }
    private val fft = Fft(nFft)
    private val frameRe = DoubleArray(nFft)
    private val frameIm = DoubleArray(nFft)

    /** The overlap-added samples of the stream that the next rows still add to. */
    private val pendingAudio = DoubleArray(overlap)

    /** The overlap-added window² of those samples, which divides them once they are complete. */
    private val pendingEnvelope = DoubleArray(overlap)

    private var started = false
    private var leadingSamplesToDrop = trim

    /**
     * Adds whole rows (a multiple of [nFft] + 2 floats) to the stream; returns the samples that no
     * later row changes.
     */
    fun push(stftRows: FloatArray): FloatArray {
        require(stftRows.size % rowLength == 0) {
            "rows of $rowLength floats expected, got ${stftRows.size} floats"
        }
        val rowCount = stftRows.size / rowLength
        if (rowCount == 0) {
            return FloatArray(0)
        }

        // The pending sums, then every row of this push added in order, as istft_same_padding does
        val length = overlap + rowCount * hop
        val audio = DoubleArray(length)
        val envelope = DoubleArray(length)
        pendingAudio.copyInto(audio)
        pendingEnvelope.copyInto(envelope)
        for (row in 0 until rowCount) {
            inverseTransform(stftRows, row)
            val start = row * hop
            for (index in 0 until nFft) {
                audio[start + index] += frameRe[index] * window[index]
                envelope[start + index] += windowSquared[index]
            }
        }
        started = true

        // Samples before the start of the next row's frame are complete
        val completeLength = rowCount * hop
        audio.copyInto(pendingAudio, startIndex = completeLength)
        envelope.copyInto(pendingEnvelope, startIndex = completeLength)
        return normalize(audio, envelope, completeLength)
    }

    /** Ends the stream: returns the samples held back for later rows. The next push starts a new stream. */
    fun finish(): FloatArray {
        val remaining = if (started) {
            // No row adds to the held-back samples any more: all but the trailing trim are complete
            normalize(pendingAudio, pendingEnvelope, overlap - trim)
        } else {
            FloatArray(0)
        }

        pendingAudio.fill(0.0)
        pendingEnvelope.fill(0.0)
        started = false
        leadingSamplesToDrop = trim
        return remaining
    }

    /** The inverse real FFT of [row], not yet windowed, into [frameRe]. */
    private fun inverseTransform(stftRows: FloatArray, row: Int) {
        val logMagnitudes = row * rowLength
        val phases = logMagnitudes + bins
        for (bin in 0 until bins) {
            val magnitude = exp(stftRows[logMagnitudes + bin].toDouble())
            val phase = stftRows[phases + bin].toDouble()
            frameRe[bin] = magnitude * cos(phase)
            frameIm[bin] = magnitude * sin(phase)
        }

        // A real signal's spectrum is Hermitian: the upper bins mirror the lower ones
        frameIm[0] = 0.0
        frameIm[bins - 1] = 0.0
        for (bin in 1 until bins - 1) {
            frameRe[nFft - bin] = frameRe[bin]
            frameIm[nFft - bin] = -frameIm[bin]
        }
        fft.inverse(frameRe, frameIm)
    }

    /** The first [count] samples divided by their envelope, less those of the stream's leading trim. */
    private fun normalize(audio: DoubleArray, envelope: DoubleArray, count: Int): FloatArray {
        val dropped = min(leadingSamplesToDrop, count)
        leadingSamplesToDrop -= dropped
        return FloatArray(count - dropped) { index ->
            val position = dropped + index
            (audio[position] / envelope[position]).toFloat()
        }
    }
}
