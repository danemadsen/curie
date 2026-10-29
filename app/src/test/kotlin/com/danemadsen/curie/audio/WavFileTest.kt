package com.danemadsen.curie.audio

import com.danemadsen.curie.engine.media.Resampler
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WavFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun ramp(count: Int) = FloatArray(count) { index -> (index % 200 - 100) / 100f }

    @Test
    fun monoSpeechRoundTrips() {
        val file = folder.newFile("mono.wav")
        val samples = ramp(16_000)

        WavFile.write(file, samples, sampleRate = 16_000)
        val audio = WavFile.read(file)

        assertEquals(16_000, audio.sampleRate)
        assertEquals(1, audio.channels)
        assertEquals(1_000L, audio.durationMs)
        assertEquals(44L + samples.size * 2, file.length())
        // Written at 32,767 steps a side and read back over 32,768: within two steps
        for (index in samples.indices) {
            assertTrue(abs(samples[index] - audio.samples[index]) <= 2f / 32_768, "sample $index")
        }
        assertEquals(WavFormat(16_000, 1, 16, isFloat = false), WavFile.readFormat(file))
    }

    @Test
    fun stereoKeepsItsChannels() {
        val file = folder.newFile("stereo.wav")
        val samples = ramp(4_800)

        WavFile.write(file, samples, sampleRate = 24_000, channels = 2)
        val audio = WavFile.read(file)

        assertEquals(2, audio.channels)
        assertEquals(2_400, audio.frameCount)
        assertEquals(100L, audio.durationMs)
    }

    @Test
    fun readingAsMonoAveragesTheChannelsOfEachFrame() {
        val file = folder.newFile("three-channels.wav")
        val samples = ramp(30_000)
        WavFile.write(file, samples, sampleRate = 48_000, channels = 3)

        val mono = WavFile.readMono(file)
        val interleaved = WavFile.read(file)

        // The same as averaging what read() gives, frame by frame
        assertEquals(1, mono.channels)
        assertEquals(48_000, mono.sampleRate)
        assertEquals(10_000, mono.frameCount)
        val expected = Resampler.toMono(interleaved.samples, channels = 3)
        assertContentEquals(expected, mono.samples)
    }

    @Test
    fun samplesBeyondFullScaleAreClipped() {
        assertEquals(32_767, WavFile.toPcm16(1.5f).toInt())
        assertEquals(-32_767, WavFile.toPcm16(-2f).toInt())
        assertEquals(0, WavFile.toPcm16(0f).toInt())
    }

    @Test
    fun otherEncodingsAndExtraChunksAreRead() {
        // 24-bit PCM after a LIST chunk of odd length (with its pad byte)
        val pcm24 = ByteBuffer.allocate(6).order(ByteOrder.LITTLE_ENDIAN)
        putInt24(pcm24, 4_194_304)
        putInt24(pcm24, -8_388_608)
        val file24 = folder.newFile("pcm24.wav")
        file24.writeBytes(wav(formatTag = 1, bitsPerSample = 24, data = pcm24.array(), extraChunk = true))

        val audio24 = WavFile.read(file24)
        assertEquals(0.5f, audio24.samples[0])
        assertEquals(-1f, audio24.samples[1])

        // 32-bit float
        val float32 = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
        float32.putFloat(0.25f)
        float32.putFloat(-0.75f)
        val fileFloat = folder.newFile("float.wav")
        fileFloat.writeBytes(wav(formatTag = 3, bitsPerSample = 32, data = float32.array(), extraChunk = false))

        val audioFloat = WavFile.read(fileFloat)
        assertEquals(listOf(0.25f, -0.75f), audioFloat.samples.toList())
        assertEquals(WavFormat(8_000, 1, 32, isFloat = true), WavFile.readFormat(fileFloat))
    }

    @Test
    fun aDataChunkLongerThanTheFileReadsWhatIsThere() {
        val output = ByteArrayOutputStream()
        val header = WavFile.header(sampleRate = 16_000, channels = 1, dataBytes = 1_000_000)
        output.write(header)
        output.write(ByteArray(10))
        val file = folder.newFile("streamed.wav")
        file.writeBytes(output.toByteArray())

        assertEquals(5, WavFile.read(file).samples.size)
    }

    @Test
    fun otherFilesAreNotWav() {
        val file = folder.newFile("text.wav")
        file.writeText("this is not audio, it is a text file long enough to have a header")

        assertNull(WavFile.readFormat(file))
        assertFailsWith<UnsupportedWavException> { WavFile.read(file) }
    }

    @Test
    fun aStreamGetsTheSameBytesAsAFile() {
        val samples = ramp(1_000)
        val file = folder.newFile("file.wav")
        WavFile.write(file, samples, sampleRate = 16_000)
        val stream = ByteArrayOutputStream()

        WavFile.write(stream, samples, sampleRate = 16_000)

        assertEquals(file.readBytes().toList(), stream.toByteArray().toList())
    }

    private fun putInt24(buffer: ByteBuffer, value: Int) {
        buffer.put((value and 0xFF).toByte())
        buffer.put((value shr 8 and 0xFF).toByte())
        buffer.put((value shr 16 and 0xFF).toByte())
    }

    /** A mono 8 kHz WAV file of [data], optionally with a 3-byte LIST chunk before the data. */
    private fun wav(formatTag: Int, bitsPerSample: Int, data: ByteArray, extraChunk: Boolean): ByteArray {
        val output = ByteArrayOutputStream()
        val body = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
        body.put("WAVE".toByteArray())
        body.put("fmt ".toByteArray())
        body.putInt(16)
        body.putShort(formatTag.toShort())
        body.putShort(1)
        body.putInt(8_000)
        body.putInt(8_000 * bitsPerSample / 8)
        body.putShort((bitsPerSample / 8).toShort())
        body.putShort(bitsPerSample.toShort())
        if (extraChunk) {
            body.put("LIST".toByteArray())
            body.putInt(3)
            body.put(byteArrayOf(1, 2, 3, 0))
        }
        body.put("data".toByteArray())
        body.putInt(data.size)
        body.put(data)

        output.write("RIFF".toByteArray())
        val size = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
        size.putInt(body.position())
        output.write(size.array())
        output.write(body.array(), 0, body.position())
        return output.toByteArray()
    }
}
