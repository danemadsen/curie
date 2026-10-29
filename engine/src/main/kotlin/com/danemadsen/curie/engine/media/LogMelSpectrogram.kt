package com.danemadsen.curie.engine.media

import com.danemadsen.curie.engine.Lfm3Omni
import kotlin.math.log10
import kotlin.math.max

/**
 * The audio encoder's input features: liquidonnx's log_mel (the Lfm3OmniFeatureExtractor front
 * end) of a 16 kHz mono clip.
 *
 * The clip is right-padded with zeros to [MediaGeometry.melFrameCount] frames of
 * [Lfm3Omni.MEL_N_FFT] samples every [Lfm3Omni.MEL_HOP_LENGTH]. Each frame is weighted by the
 * periodic Hann window rounded to float32 (liquidonnx's stand-in for torch.hann_window), its power
 * spectrum |rfft|² is computed in double and goes through the Slaney mel filter bank, then log10
 * floored at [Lfm3Omni.MEL_LOG_FLOOR] and (x + 4) / 4. The result equals liquidonnx's bit for bit
 * on the golden clips.
 */
object LogMelSpectrogram {
    private const val N_FFT = Lfm3Omni.MEL_N_FFT
    private const val HOP_LENGTH = Lfm3Omni.MEL_HOP_LENGTH
    private const val FREQUENCY_BINS = N_FFT / 2 + 1

    private val window: DoubleArray = analysisWindow()
    private val fft = Fft(N_FFT)

    internal val filterBank = MelFilterBank(
        frequencyBins = FREQUENCY_BINS,
        melBins = Lfm3Omni.MEL_BINS,
        minFrequency = 0.0,
        maxFrequency = Lfm3Omni.INPUT_SAMPLE_RATE / 2.0,
        sampleRate = Lfm3Omni.INPUT_SAMPLE_RATE,
    )

    /**
     * The input_features of [samples] (16 kHz mono): [Lfm3Omni.MEL_BINS] x T values, row-major by
     * mel bin (T values of bin 0 first), with T = MediaGeometry.melFrameCount(samples.size).
     */
    fun compute(samples: FloatArray): FloatArray {
        val frameCount = MediaGeometry.melFrameCount(samples.size)
        val features = FloatArray(Lfm3Omni.MEL_BINS * frameCount)
        val re = DoubleArray(N_FFT)
        val im = DoubleArray(N_FFT)
        val power = DoubleArray(FREQUENCY_BINS)

        for (frame in 0 until frameCount) {
            val start = frame * HOP_LENGTH
            for (index in 0 until N_FFT) {
                val sample = start + index
                re[index] = if (sample < samples.size) samples[sample] * window[index] else 0.0
                im[index] = 0.0
            }
            fft.forward(re, im)
            for (bin in 0 until FREQUENCY_BINS) {
                power[bin] = re[bin] * re[bin] + im[bin] * im[bin]
            }

            for (mel in 0 until Lfm3Omni.MEL_BINS) {
                val energy = filterBank.energy(mel, power)
                // log10(0) is -infinity, which the floor lifts
                val logEnergy = max(log10(energy), Lfm3Omni.MEL_LOG_FLOOR)
                val normalized = (logEnergy + Lfm3Omni.MEL_NORMALIZATION_OFFSET) / Lfm3Omni.MEL_NORMALIZATION_DIVISOR
                features[mel * frameCount + frame] = normalized.toFloat()
            }
        }
        return features
    }

    /** torch.hann_window(MEL_WIN_LENGTH) in float32, centred in MEL_N_FFT samples as torch.stft pads it. */
    private fun analysisWindow(): DoubleArray {
        val hann = SignalWindows.periodicHann(Lfm3Omni.MEL_WIN_LENGTH)
        val offset = (N_FFT - Lfm3Omni.MEL_WIN_LENGTH) / 2
        val window = DoubleArray(N_FFT)
        for (index in hann.indices) {
            window[offset + index] = hann[index].toFloat().toDouble()
        }
        return window
    }
}
