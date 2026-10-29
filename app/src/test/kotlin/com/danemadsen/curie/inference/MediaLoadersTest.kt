package com.danemadsen.curie.inference

import android.media.AudioFormat
import androidx.exifinterface.media.ExifInterface
import com.danemadsen.curie.audio.SampleBuffer
import com.danemadsen.curie.audio.WavFile
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaLoadersTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun imagesDecodeAtTheSmallestPowerOfTwoThatKeepsTwiceTheTarget() {
        // A 4000 x 3000 photo for a 576 x 416 target: 1/2 keeps 2000 x 1500, 1/4 would give 1000 x 750
        assertEquals(2, ImageLoading.sampleSize(4_000, 3_000, minimumWidth = 1_152, minimumHeight = 832))

        // Already small: decoded whole
        assertEquals(1, ImageLoading.sampleSize(800, 600, minimumWidth = 1_152, minimumHeight = 832))

        // Very large: down by eight
        assertEquals(8, ImageLoading.sampleSize(12_000, 9_000, minimumWidth = 1_152, minimumHeight = 832))
    }

    @Test
    fun quarterTurnsSwapTheSides() {
        assertTrue(ImageLoading.isQuarterTurn(ExifInterface.ORIENTATION_ROTATE_90))
        assertTrue(ImageLoading.isQuarterTurn(ExifInterface.ORIENTATION_TRANSVERSE))
        assertFalse(ImageLoading.isQuarterTurn(ExifInterface.ORIENTATION_ROTATE_180))
        assertFalse(ImageLoading.isQuarterTurn(ExifInterface.ORIENTATION_NORMAL))
    }

    @Test
    fun translucentPixelsAreBlendedOverWhite() {
        val pixels = intArrayOf(
            0xFF102030.toInt(),
            0x00000000,
            0x80000000.toInt(),
        )
        val rgb = ByteArray(9)

        BitmapPixels.argbToRgb(pixels, rgb, offset = 0)

        val values = rgb.map { byte -> byte.toInt() and 0xFF }
        assertEquals(listOf(0x10, 0x20, 0x30, 255, 255, 255, 127, 127, 127), values)
    }

    @Test
    fun decodedAudioIsCutOrPaddedToTheDeclaredLength() {
        val samples = floatArrayOf(0.1f, 0.2f, 0.3f)

        assertContentEquals(floatArrayOf(0.1f, 0.2f), AudioDecoding.fitLength(samples, 2))
        assertContentEquals(floatArrayOf(0.1f, 0.2f, 0.3f, 0f), AudioDecoding.fitLength(samples, 4))
    }

    @Test
    fun codecOutputBecomesMonoFloats() {
        // Two stereo frames of 16-bit PCM in the platform's order, then half a frame left over
        val pcm16 = ByteBuffer.allocate(10).order(ByteOrder.nativeOrder())
        pcm16.putShort(16_384)
        pcm16.putShort(0)
        pcm16.putShort(-32_768)
        pcm16.putShort(-32_768)
        pcm16.putShort(7)
        pcm16.flip()
        val fromPcm16 = SampleBuffer()

        AudioDecoding.appendMono(pcm16, AudioFormat.ENCODING_PCM_16BIT, channels = 2, target = fromPcm16)

        assertContentEquals(floatArrayOf(0.25f, -1f), fromPcm16.toArray())

        // Float output, mono
        val pcmFloat = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder())
        pcmFloat.putFloat(0.5f)
        pcmFloat.putFloat(-0.125f)
        pcmFloat.flip()
        val fromFloat = SampleBuffer()

        AudioDecoding.appendMono(pcmFloat, AudioFormat.ENCODING_PCM_FLOAT, channels = 1, target = fromFloat)

        assertContentEquals(floatArrayOf(0.5f, -0.125f), fromFloat.toArray())
    }

    @Test
    fun aStereoWavIsHeardAt16kMonoWithExactlyTheDeclaredLength() {
        // One second at 44.1 kHz: a 440 Hz tone on the left, silence on the right
        val frames = 44_100
        val interleaved = FloatArray(frames * 2)
        for (frame in 0 until frames) {
            val time = frame.toDouble() / frames
            interleaved[frame * 2] = (0.5 * sin(2 * PI * 440 * time)).toFloat()
        }
        val file = folder.newFile("clip.wav")
        WavFile.write(file, interleaved, sampleRate = 44_100, channels = 2)

        val audio = AudioDecoding.decode16kMono(file, sampleCount = 16_000)

        // The channels are averaged: the tone at half its level (RMS of a 0.25 sine), away from the edges
        assertEquals(16_000, audio.size)
        val middle = audio.copyOfRange(2_000, 14_000)
        val rms = sqrt(middle.sumOf { sample -> sample.toDouble() * sample } / middle.size)
        assertEquals(0.25 / sqrt(2.0), rms, 0.005)

        // Whatever the file holds, the model gets the length the prompt was built for
        assertEquals(15_000, AudioDecoding.decode16kMono(file, sampleCount = 15_000).size)
        val padded = AudioDecoding.decode16kMono(file, sampleCount = 17_000)
        assertEquals(17_000, padded.size)
        assertEquals(0f, padded.last())
    }
}
