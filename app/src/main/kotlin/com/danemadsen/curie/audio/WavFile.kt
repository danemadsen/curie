package com.danemadsen.curie.audio

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Samples in [-1, 1], interleaved by channel ([channels] side by side per frame). */
class PcmAudio(
    val samples: FloatArray,
    val sampleRate: Int,
    val channels: Int,
) {
    init {
        require(sampleRate > 0) { "the sample rate must be positive, got $sampleRate" }
        require(channels > 0) { "audio needs at least one channel, got $channels" }
        require(samples.size % channels == 0) { "${samples.size} samples are not whole frames of $channels channels" }
    }

    val frameCount: Int
        get() = samples.size / channels

    val durationMs: Long
        get() = frameCount * 1000L / sampleRate
}

/** What the header of a WAV file says about its samples. */
data class WavFormat(
    val sampleRate: Int,
    val channels: Int,
    val bitsPerSample: Int,
    val isFloat: Boolean,
)

/** A file that is not a WAV file this reader understands. */
class UnsupportedWavException(message: String) : IOException(message)

/**
 * WAV (RIFF) files of PCM samples. Writes 16-bit PCM; reads 8, 16, 24 and 32-bit integer PCM and
 * 32-bit float, WAVE_FORMAT_EXTENSIBLE included, skipping chunks it does not need.
 */
object WavFile {
    private const val RIFF_HEADER_BYTES = 12
    private const val FMT_CHUNK_BYTES = 16
    private const val CHUNK_HEADER_BYTES = 8
    private const val HEADER_BYTES = RIFF_HEADER_BYTES + CHUNK_HEADER_BYTES + FMT_CHUNK_BYTES + CHUNK_HEADER_BYTES
    private const val FORMAT_PCM = 1
    private const val FORMAT_FLOAT = 3
    private const val FORMAT_EXTENSIBLE = 0xFFFE
    private const val PCM16_MAX = 32767f
    private const val COPY_SAMPLES = 8192

    /** Writes [samples] (mono, or interleaved [channels]) as 16-bit PCM, replacing [file]. */
    fun write(file: File, samples: FloatArray, sampleRate: Int, channels: Int = 1) {
        FileOutputStream(file).use { output ->
            val buffered = BufferedOutputStream(output)
            write(buffered, samples, sampleRate, channels)
            buffered.flush()
        }
    }

    /** Writes a whole WAV file to [output] (left open). */
    fun write(output: OutputStream, samples: FloatArray, sampleRate: Int, channels: Int = 1) {
        val audio = PcmAudio(samples, sampleRate, channels)
        val dataBytes = audio.samples.size * 2
        val header = header(sampleRate, channels, dataBytes)
        output.write(header)

        val chunk = ByteBuffer.allocate(COPY_SAMPLES * 2)
        chunk.order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        while (offset < samples.size) {
            chunk.clear()
            val end = minOf(offset + COPY_SAMPLES, samples.size)
            for (index in offset until end) {
                chunk.putShort(toPcm16(samples[index]))
            }
            output.write(chunk.array(), 0, chunk.position())
            offset = end
        }
    }

    /** The format of [file], or null when it is not a WAV file of a supported encoding. */
    fun readFormat(file: File): WavFormat? {
        return try {
            openData(file) { format, _, _ -> format }
        } catch (error: IOException) {
            null
        }
    }

    /** Reads all samples of [file]. Throws [UnsupportedWavException] or another IOException. */
    fun read(file: File): PcmAudio = openData(file) { format, dataBytes, input ->
        readSamples(format, dataBytes, input, toMono = false)
    }

    /**
     * Reads [file] with the channels of each frame averaged into one, without holding all channels
     * at once (a long stereo clip needs half the memory). Throws like [read].
     */
    fun readMono(file: File): PcmAudio = openData(file) { format, dataBytes, input ->
        readSamples(format, dataBytes, input, toMono = true)
    }

    /** The 44-byte header of a 16-bit PCM file whose data chunk holds [dataBytes]. */
    internal fun header(sampleRate: Int, channels: Int, dataBytes: Int): ByteArray {
        val blockAlign = channels * 2
        val header = ByteBuffer.allocate(HEADER_BYTES)
        header.order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt(HEADER_BYTES - CHUNK_HEADER_BYTES + dataBytes)
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(FMT_CHUNK_BYTES)
        header.putShort(FORMAT_PCM.toShort())
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * blockAlign)
        header.putShort(blockAlign.toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes)
        return header.array()
    }

    internal fun toPcm16(sample: Float): Short {
        val clipped = sample.coerceIn(-1f, 1f)
        return (clipped * PCM16_MAX).roundToInt().toShort()
    }

    /**
     * Opens [file], parses the header up to the data chunk and passes the format, the data chunk's
     * length (cut to what the file holds) and the stream positioned at the samples to [block].
     */
    private fun <T> openData(file: File, block: (WavFormat, Long, DataInputStream) -> T): T {
        FileInputStream(file).use { stream ->
            val input = DataInputStream(BufferedInputStream(stream))
            val riff = readTag(input)
            input.skipFully(4)
            val wave = readTag(input)
            if (riff != "RIFF" || wave != "WAVE") {
                throw UnsupportedWavException("${file.name} is not a RIFF WAVE file")
            }

            var format: WavFormat? = null
            var position = RIFF_HEADER_BYTES.toLong()
            while (true) {
                val tag = readTag(input)
                val size = readUInt32(input)
                position += CHUNK_HEADER_BYTES
                when (tag) {
                    "fmt " -> {
                        format = readFormatChunk(input, size)
                    }
                    "data" -> {
                        val knownFormat = format ?: throw UnsupportedWavException("${file.name} has no fmt chunk")
                        val available = file.length() - position
                        val dataBytes = minOf(size, available)
                        return block(knownFormat, dataBytes, input)
                    }
                    else -> {
                        input.skipFully(size)
                    }
                }
                position += size

                // Chunks are word-aligned: an odd chunk is followed by a pad byte
                if (size % 2 == 1L) {
                    input.skipFully(1)
                    position += 1
                }
            }
        }
    }

    private fun readFormatChunk(input: DataInputStream, size: Long): WavFormat {
        if (size < FMT_CHUNK_BYTES) {
            throw UnsupportedWavException("the fmt chunk is too short: $size bytes")
        }
        val bytes = ByteArray(size.toInt())
        input.readFully(bytes)
        val chunk = ByteBuffer.wrap(bytes)
        chunk.order(ByteOrder.LITTLE_ENDIAN)

        var formatTag = chunk.getShort(0).toInt() and 0xFFFF
        val channels = chunk.getShort(2).toInt() and 0xFFFF
        val sampleRate = chunk.getInt(4)
        val bitsPerSample = chunk.getShort(14).toInt() and 0xFFFF

        // WAVE_FORMAT_EXTENSIBLE names the real format in the first two bytes of its sub-format GUID
        val hasSubFormat = formatTag == FORMAT_EXTENSIBLE && size >= 26
        if (hasSubFormat) {
            formatTag = chunk.getShort(24).toInt() and 0xFFFF
        }

        val isFloat = formatTag == FORMAT_FLOAT
        val isSupportedPcm = formatTag == FORMAT_PCM && bitsPerSample in listOf(8, 16, 24, 32)
        val isSupportedFloat = isFloat && bitsPerSample == 32
        if (!isSupportedPcm && !isSupportedFloat) {
            throw UnsupportedWavException("unsupported WAV encoding: format $formatTag, $bitsPerSample bits")
        }
        if (channels <= 0 || sampleRate <= 0) {
            throw UnsupportedWavException("invalid WAV format: $channels channels at $sampleRate Hz")
        }
        return WavFormat(sampleRate, channels, bitsPerSample, isFloat)
    }

    /**
     * The samples of the data chunk, whole frames at a time: interleaved as stored or, with
     * [toMono], each frame's channels averaged into one.
     */
    private fun readSamples(format: WavFormat, dataBytes: Long, input: InputStream, toMono: Boolean): PcmAudio {
        val channels = format.channels
        val bytesPerSample = format.bitsPerSample / 8
        val frameBytes = bytesPerSample * channels
        val frameCount = dataBytes / frameBytes
        val outputChannels = if (toMono) 1 else channels
        val sampleCount = frameCount * outputChannels
        if (sampleCount > Int.MAX_VALUE) {
            throw UnsupportedWavException("the WAV file is too long: $sampleCount samples")
        }

        val samples = FloatArray(sampleCount.toInt())
        val framesPerChunk = maxOf(1, COPY_SAMPLES / channels)
        val bytes = ByteArray(framesPerChunk * frameBytes)
        var framesRead = 0L
        var filled = 0
        while (framesRead < frameCount) {
            val frames = minOf(framesPerChunk.toLong(), frameCount - framesRead).toInt()
            val byteCount = frames * frameBytes
            readFully(input, bytes, byteCount)
            val chunk = ByteBuffer.wrap(bytes, 0, byteCount)
            chunk.order(ByteOrder.LITTLE_ENDIAN)
            repeat(frames) {
                if (toMono) {
                    samples[filled] = readMonoFrame(chunk, format)
                    filled++
                } else {
                    repeat(channels) {
                        samples[filled] = readSample(chunk, format)
                        filled++
                    }
                }
            }
            framesRead += frames
        }
        return PcmAudio(samples, format.sampleRate, outputChannels)
    }

    /** The channels of one frame averaged in double precision, as the engine's Resampler.toMono does. */
    private fun readMonoFrame(chunk: ByteBuffer, format: WavFormat): Float {
        var sum = 0.0
        repeat(format.channels) {
            sum += readSample(chunk, format)
        }
        return (sum / format.channels).toFloat()
    }

    private fun readSample(chunk: ByteBuffer, format: WavFormat): Float {
        if (format.isFloat) {
            return chunk.getFloat()
        }
        return when (format.bitsPerSample) {
            8 -> ((chunk.get().toInt() and 0xFF) - 128) / 128f
            16 -> chunk.getShort() / 32768f
            24 -> {
                val low = chunk.get().toInt() and 0xFF
                val middle = chunk.get().toInt() and 0xFF
                val high = chunk.get().toInt()
                val value = (high shl 16) or (middle shl 8) or low
                value / 8_388_608f
            }
            else -> chunk.getInt() / 2_147_483_648f
        }
    }

    private fun readTag(input: DataInputStream): String {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        return String(bytes, Charsets.US_ASCII)
    }

    private fun readUInt32(input: DataInputStream): Long {
        val bytes = ByteArray(4)
        input.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        return buffer.getInt().toLong() and 0xFFFFFFFFL
    }

    private fun readFully(input: InputStream, bytes: ByteArray, count: Int) {
        var read = 0
        while (read < count) {
            val result = input.read(bytes, read, count - read)
            if (result < 0) {
                throw EOFException("the WAV data ends early")
            }
            read += result
        }
    }

    private fun DataInputStream.skipFully(count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped <= 0) {
                // skip() may stop early without reaching the end: read a byte to tell
                val value = read()
                if (value < 0) {
                    throw EOFException("the WAV file ends inside a chunk")
                }
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }
}
