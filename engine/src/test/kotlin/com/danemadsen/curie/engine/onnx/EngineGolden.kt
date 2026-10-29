package com.danemadsen.curie.engine.onnx

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ContentPart
import com.danemadsen.curie.engine.chat.ToolDefinition
import com.danemadsen.curie.engine.media.RgbImage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

/**
 * The golden data that tools/golden/generate-engine-golden.py writes with liquidonnx's reference
 * inference to src/test/resources/golden/engine.
 */
internal object EngineGolden {
    private const val ROOT = "/golden/engine/"

    private val json = Json { ignoreUnknownKeys = true }

    val manifest: Manifest by lazy {
        val text = bytes("manifest.json").decodeToString()
        json.decodeFromString<Manifest>(text)
    }

    fun generation(name: String): Generation = manifest.generations.single { generation -> generation.name == name }

    fun bytes(path: String): ByteArray {
        val stream = EngineGolden::class.java.getResourceAsStream(ROOT + path)
        checkNotNull(stream) { "missing golden file $path: run tools/golden/generate-engine-golden.py" }
        return stream.use { input -> input.readBytes() }
    }

    fun floats(path: String): FloatArray {
        val buffer = ByteBuffer.wrap(bytes(path)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val values = FloatArray(buffer.remaining())
        buffer.get(values)
        return values
    }

    /** A 16-bit mono WAV as samples in [-1, 1) (int16 / 32768, as the reference's load_audio). */
    fun wavSamples(path: String): FloatArray {
        val stream = AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes(path)))
        val format = stream.format
        val isMono16Bit = format.channels == 1 && format.sampleSizeInBits == 16
        val isSignedPcm = format.encoding == AudioFormat.Encoding.PCM_SIGNED
        check(isMono16Bit && isSignedPcm) { "expected 16-bit mono PCM, got $format" }
        val data = stream.use { input -> input.readBytes() }
        val order = if (format.isBigEndian) ByteOrder.BIG_ENDIAN else ByteOrder.LITTLE_ENDIAN
        val shorts = ByteBuffer.wrap(data).order(order).asShortBuffer()
        return FloatArray(shorts.remaining()) { index -> shorts.get(index) / 32_768f }
    }

    /** The red disk on blue of the golden script's test_image, drawn from the same formula. */
    fun testImage(size: Int): RgbImage {
        val pixels = ByteArray(size * size * 3)
        val center = size / 2
        for (y in 0 until size) {
            for (x in 0 until size) {
                val inside = (x - center) * (x - center) + (y - center) * (y - center) < DISK_RADIUS * DISK_RADIUS
                val color = if (inside) DISK_COLOR else BACKGROUND_COLOR
                val offset = (y * size + x) * 3
                for (channel in 0 until 3) {
                    pixels[offset + channel] = color[channel].toByte()
                }
            }
        }
        return RgbImage(size, size, pixels)
    }

    fun sha256(values: FloatArray): String {
        val bytes = ByteBuffer.allocate(values.size * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asFloatBuffer().put(values)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes.array())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    fun maxAbsDifference(expected: FloatArray, actual: FloatArray): Double {
        check(expected.size == actual.size) { "${expected.size} values expected, got ${actual.size}" }
        var maximum = 0.0
        for (index in expected.indices) {
            maximum = maxOf(maximum, abs(expected[index].toDouble() - actual[index]))
        }
        return maximum
    }

    /** The chat messages of a golden case: plain text contents. */
    fun messages(generation: Generation): List<ChatMessage> =
        generation.messages.map { message ->
            val role = ChatRole.entries.single { role -> role.label == message.getValue("role").jsonPrimitive.content }
            val content = message.getValue("content").jsonPrimitive.content
            ChatMessage(role, listOf(ContentPart.Text(content)))
        }

    /** The tool definitions of a golden case (OpenAI's {"type": "function", "function": ...}). */
    fun tools(generation: Generation): List<ToolDefinition> =
        generation.tools.map { tool ->
            val function = tool.getValue("function").jsonObject
            ToolDefinition(
                name = function.getValue("name").jsonPrimitive.content,
                description = function.getValue("description").jsonPrimitive.content,
                parameters = function.getValue("parameters").jsonObject,
            )
        }

    private const val DISK_RADIUS = 80
    private val DISK_COLOR = intArrayOf(220, 30, 30)
    private val BACKGROUND_COLOR = intArrayOf(40, 90, 200)

    @Serializable
    data class Manifest(
        val generator: String,
        val audioSampling: AudioSamplingCase,
        val generations: List<Generation>,
        val audio: AudioCase,
        val vision: VisionCase,
        val codec: CodecCase,
    )

    @Serializable
    data class AudioSamplingCase(val temperature: Float, val repetitionPenalty: Float, val repetitionWindow: Int)

    @Serializable
    data class Generation(
        val name: String,
        val mode: String,
        val maxSlots: Int,
        val prompt: List<Int>,
        val checkpoints: List<Int>,
        val chunks: List<List<Int>>,
        val marker: Int?,
        val slots: List<Int>,
        val frames: List<List<Int>>,
        val text: String,
        val messages: List<JsonObject> = emptyList(),
        val tools: List<JsonObject> = emptyList(),
        val question: String? = null,
    ) {
        /** Whether the reference's answer ended its turn, rather than stopping at the slot limit. */
        val endedTurn: Boolean
            get() = slots.size < maxSlots || slots.last() == Lfm3Omni.IM_END_TOKEN

        /** The reference's answer: its slots without the closing <|im_end|>, which content leaves out. */
        val answerSlots: List<Int>
            get() = if (slots.last() == Lfm3Omni.IM_END_TOKEN) slots.dropLast(1) else slots
    }

    @Serializable
    data class AudioCase(
        val clip: String,
        val sampleCount: Int,
        val melFrames: Int,
        val melSha256: String,
        val rowCount: Int,
        val rows: List<Int>,
        val expected: String,
    )

    @Serializable
    data class VisionCase(
        val size: Int,
        val pixelValuesSha256: String,
        val rowCount: Int,
        val rows: List<Int>,
        val expected: String,
    )

    @Serializable
    data class CodecCase(
        val frames: List<List<Int>>,
        val expected: String,
        val longFrames: List<List<Int>>,
    )
}
