package com.danemadsen.curie.engine.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The golden data that tools/golden/generate-media-golden.py writes to
 * src/test/resources/golden/media: raw uint8 RGB (`.rgb`) and little-endian float32 (`.f32`) files
 * listed in manifest.json.
 */
internal object MediaGolden {
    private const val ROOT = "/golden/media/"

    /** scipy.signal.resample_poly: the [ResampleCase.reference] of the band-limited signals. */
    const val RESAMPLE_POLY = "resample_poly"

    /** soxr's HQ preset: the [ResampleCase.reference] of the full-band speech. */
    const val SOXR_HQ = "soxr_hq"

    val manifest: Manifest by lazy {
        val text = bytes("manifest.json").decodeToString()
        Json.decodeFromString<Manifest>(text)
    }

    fun bytes(path: String): ByteArray {
        val stream = MediaGolden::class.java.getResourceAsStream(ROOT + path)
        checkNotNull(stream) { "missing golden file $path: run tools/golden/generate-media-golden.py" }
        return stream.use { input -> input.readBytes() }
    }

    fun floats(path: String): FloatArray {
        val data = bytes(path)
        val buffer = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val floatBuffer = buffer.asFloatBuffer()
        val values = FloatArray(floatBuffer.remaining())
        floatBuffer.get(values)
        return values
    }

    fun image(path: String, width: Int, height: Int): RgbImage = RgbImage(width, height, bytes(path))

    @Serializable
    data class Manifest(
        val generator: String,
        val versions: Map<String, String>,
        val resize: List<ResizeCase>,
        val imagePatches: List<ImagePatchCase>,
        val videoPatches: List<VideoPatchCase>,
        val melFilterBank: MelFilterBankCase,
        val logMel: List<LogMelCase>,
        val inverseStft: List<InverseStftCase>,
        val resample: List<ResampleCase>,
    )

    @Serializable
    data class ResizeCase(
        val name: String,
        val source: String,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val width: Int,
        val height: Int,
        val expected: String,
    )

    /** [maxTokens] is null when the source is already at a resize target (patchify alone). */
    @Serializable
    data class ImagePatchCase(
        val name: String,
        val source: String,
        val sourceWidth: Int,
        val sourceHeight: Int,
        val maxTokens: Int?,
        val rows: Int,
        val columns: Int,
        val expected: String,
    )

    @Serializable
    data class VideoPatchCase(
        val name: String,
        val frames: List<String>,
        val frameWidth: Int,
        val frameHeight: Int,
        val targetWidth: Int,
        val targetHeight: Int,
        val rows: Int,
        val columns: Int,
        val expected: List<String>,
    )

    /** [weights] holds `[frequencyBins, melBins]` values row-major, as transformers returns them. */
    @Serializable
    data class MelFilterBankCase(
        val frequencyBins: Int,
        val melBins: Int,
        val weights: String,
    )

    @Serializable
    data class LogMelCase(
        val name: String,
        val samples: String,
        val sampleCount: Int,
        val frames: Int,
        val expected: String,
    )

    @Serializable
    data class InverseStftCase(
        val name: String,
        val rows: String,
        val rowCount: Int,
        val nFft: Int,
        val hop: Int,
        val expected: String,
    )

    /** [reference] names the resampler whose output [expected] holds: [RESAMPLE_POLY] or [SOXR_HQ]. */
    @Serializable
    data class ResampleCase(
        val name: String,
        val reference: String,
        val input: String,
        val fromRate: Int,
        val toRate: Int,
        val inputLength: Int,
        val expected: String,
        val expectedLength: Int,
    )
}
