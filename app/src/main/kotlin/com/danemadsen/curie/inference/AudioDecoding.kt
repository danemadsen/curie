package com.danemadsen.curie.inference

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.danemadsen.curie.audio.PcmAudio
import com.danemadsen.curie.audio.SampleBuffer
import com.danemadsen.curie.audio.WavFile
import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.media.Resampler
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Audio files and video soundtracks as the model hears them: 16 kHz mono floats. WAV files are
 * read directly; anything else goes through MediaExtractor and MediaCodec. Either way the channels
 * are averaged into one as the samples are read (a long stereo clip never sits in memory whole),
 * then resampled with the engine's [Resampler] and padded or cut to the sample count the
 * attachment declared, which the prompt's placeholder count was derived from.
 */
internal object AudioDecoding {
    private const val TIMEOUT_US = 10_000L
    private const val MAX_IDLE_ROUNDS = 500
    private const val MICROSECONDS_PER_SECOND = 1_000_000L

    /** The decoded buffer starts at the track's length plus this much, so it rarely has to grow. */
    private const val PRESIZE_MARGIN_SECONDS = 1

    /** The largest buffer a track's claimed length starts (2.5 minutes at 48 kHz); a longer one grows as it decodes. */
    private const val MAX_PRESIZE_SAMPLES = 48_000 * 150

    /** 16-bit PCM spans [-32768, 32767]: dividing by 32768 maps it into [-1, 1). */
    private const val PCM16_SCALE = 32768.0

    fun decode16kMono(file: File, sampleCount: Int): FloatArray {
        val isWav = WavFile.readFormat(file) != null
        val mono = if (isWav) WavFile.readMono(file) else decodeWithCodec(file)
        val resampled = Resampler.resample(mono.samples, mono.sampleRate, Lfm3Omni.INPUT_SAMPLE_RATE)
        return fitLength(resampled, sampleCount)
    }

    /** [samples] cut to [length], or padded with silence up to it. */
    fun fitLength(samples: FloatArray, length: Int): FloatArray {
        if (samples.size == length) {
            return samples
        }
        return samples.copyOf(length)
    }

    /**
     * Appends the whole frames of [buffer] (MediaCodec's PCM: 16-bit, or float with
     * [AudioFormat.ENCODING_PCM_FLOAT], in the platform's byte order) to [target], each frame's
     * [channels] averaged into one sample in [-1, 1).
     */
    fun appendMono(buffer: ByteBuffer, encoding: Int, channels: Int, target: SampleBuffer) {
        buffer.order(ByteOrder.nativeOrder())
        val isFloat = encoding == AudioFormat.ENCODING_PCM_FLOAT
        val sampleBytes = if (isFloat) Float.SIZE_BYTES else Short.SIZE_BYTES
        val frameBytes = sampleBytes * channels
        while (buffer.remaining() >= frameBytes) {
            var sum = 0.0
            repeat(channels) {
                sum += if (isFloat) buffer.getFloat().toDouble() else buffer.getShort() / PCM16_SCALE
            }
            val average = sum / channels
            target.append(average.toFloat())
        }
    }

    private fun decodeWithCodec(file: File): PcmAudio {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(file.path)
            val trackIndex = audioTrackOf(extractor) ?: throw IOException("${file.name} has no audio track")
            extractor.selectTrack(trackIndex)
            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mimeType = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IOException("${file.name} has no audio type")

            val decoder = MediaCodec.createDecoderByType(mimeType)
            codec = decoder
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()
            return drain(extractor, decoder, inputFormat)
        } catch (error: IllegalStateException) {
            throw IOException("cannot decode the audio of ${file.name}", error)
        } catch (error: IllegalArgumentException) {
            throw IOException("cannot decode the audio of ${file.name}", error)
        } finally {
            codec?.release()
            extractor.release()
        }
    }

    /** Feeds the track to [decoder] and collects its PCM output, as mono, until the end of the stream. */
    private fun drain(extractor: MediaExtractor, decoder: MediaCodec, inputFormat: MediaFormat): PcmAudio {
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var encoding = AudioFormat.ENCODING_PCM_16BIT
        val expectedSamples = expectedMonoSamples(inputFormat, sampleRate)
        val output = SampleBuffer(expectedSamples)
        val info = MediaCodec.BufferInfo()
        var isInputDone = false
        var idleRounds = 0

        while (true) {
            if (!isInputDone) {
                isInputDone = queueInput(extractor, decoder)
            }

            val outputIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
            when {
                outputIndex >= 0 -> {
                    idleRounds = 0
                    val buffer = decoder.getOutputBuffer(outputIndex)
                    if (buffer != null && info.size > 0) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        appendMono(buffer, encoding, channels, output)
                    }
                    decoder.releaseOutputBuffer(outputIndex, false)
                    val isEndOfStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    if (isEndOfStream) {
                        break
                    }
                }
                outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val format = decoder.outputFormat
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        encoding = format.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
                else -> {
                    idleRounds++
                    if (idleRounds > MAX_IDLE_ROUNDS) {
                        throw IOException("the audio decoder stopped producing output")
                    }
                }
            }
        }
        return PcmAudio(output.toArray(), sampleRate, channels = 1)
    }

    /** About as many mono samples as the track holds (a second more), when its format says how long it is. */
    private fun expectedMonoSamples(format: MediaFormat, sampleRate: Int): Int {
        if (!format.containsKey(MediaFormat.KEY_DURATION)) {
            return sampleRate
        }
        val durationUs = format.getLong(MediaFormat.KEY_DURATION)
        val trackSamples = durationUs * sampleRate / MICROSECONDS_PER_SECOND
        val withMargin = trackSamples + sampleRate * PRESIZE_MARGIN_SECONDS
        return withMargin.coerceIn(sampleRate.toLong(), MAX_PRESIZE_SAMPLES.toLong()).toInt()
    }

    /** Queues the next sample of the track; returns true once the end of the stream was queued. */
    private fun queueInput(extractor: MediaExtractor, decoder: MediaCodec): Boolean {
        val inputIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
        if (inputIndex < 0) {
            return false
        }
        val buffer = decoder.getInputBuffer(inputIndex) ?: return false
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            decoder.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        decoder.queueInputBuffer(inputIndex, 0, size, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    private fun audioTrackOf(extractor: MediaExtractor): Int? {
        for (index in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(index)
            val mimeType = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mimeType.startsWith("audio/")) {
                return index
            }
        }
        return null
    }
}
