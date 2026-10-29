package com.danemadsen.curie.engine.chat

import com.danemadsen.curie.engine.Lfm3Omni
import com.danemadsen.curie.engine.media.MediaGeometry
import com.danemadsen.curie.engine.media.MediaLimits
import com.danemadsen.curie.engine.prompt.AudioInput
import com.danemadsen.curie.engine.prompt.FeatureStream
import com.danemadsen.curie.engine.prompt.ImageInput
import com.danemadsen.curie.engine.prompt.SlotSource
import com.danemadsen.curie.engine.prompt.VideoInput
import com.danemadsen.curie.engine.tokenizer.BpeTokenizer
import com.danemadsen.curie.engine.tokenizer.Goldens
import com.danemadsen.curie.engine.tokenizer.TestTokenizers
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.DynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * ChatTemplate against transformers' apply_chat_template with chat_template.jinja, rewritten as
 * Lfm3OmniProcessor rewrites it and tokenized by tokenizers (goldens), with the special-token
 * strings of the conversation's own strings kept as text (encode_special_tokens). Media
 * placeholders stay markers in the goldens; they are expanded here by hand as Lfm3OmniProcessor
 * does.
 */
class ChatTemplateGoldenTest {
    private val cases = Goldens.cases("chat-template-cases.json")

    @TestFactory
    fun rendersLikeTheJinjaTemplate(): List<DynamicNode> = TestTokenizers.sources.map { (name, load) ->
        val tests = cases.map { case ->
            val caseName = case.getValue("name").jsonPrimitive.content
            DynamicTest.dynamicTest(caseName) {
                checkCase(case, load())
            }
        }
        DynamicContainer.dynamicContainer(name, tests)
    }

    private fun checkCase(case: JsonObject, tokenizer: BpeTokenizer) {
        val messageArray = case.getValue("messages").jsonArray
        val toolArray = case.getValue("tools").jsonArray
        val messages = messageArray.map { message -> chatMessage(message.jsonObject) }
        val tools = toolArray.map { tool -> toolDefinition(tool.jsonObject) }
        val addGenerationPrompt = case.getValue("add_generation_prompt").jsonPrimitive.boolean

        val prompt = ChatTemplate.render(messages, tools, addGenerationPrompt, tokenizer)

        val expected = ExpectedPrompt(tokenizer)
        for (segment in case.getValue("expected").jsonArray) {
            val fields = segment.jsonObject
            when {
                "ids" in fields -> {
                    val ids = Goldens.ids(fields["ids"])
                    expected.addText(ids)
                }
                "media" in fields -> {
                    val key = fields.getValue("media").jsonPrimitive.content
                    val part = mediaPart(messages, key)
                    expected.addMedia(part)
                }
                else -> {
                    val index = fields.getValue("generated").jsonPrimitive.int
                    expected.addGenerated(messages[index])
                }
            }
        }

        val expectedTokens = expected.tokens.toIntArray()
        val expectedMedia = expected.mediaKeys.distinct()
        val actualMedia = prompt.media.keys.toList()
        assertContentEquals(expectedTokens, prompt.tokens, "tokens")
        assertContentEquals(expected.sources.toTypedArray(), prompt.sources, "sources")
        assertContentEquals(expected.checkpoints(), prompt.checkpoints, "checkpoints at each message and the end")
        assertEquals(expectedMedia, actualMedia, "media")
    }

    /** The prompt Lfm3OmniProcessor would build: text ids, then media expanded around span tokens. */
    private class ExpectedPrompt(private val tokenizer: BpeTokenizer) {
        val tokens = ArrayList<Int>()
        val sources = ArrayList<SlotSource?>()
        val mediaKeys = ArrayList<String>()

        fun addText(ids: IntArray) {
            for (id in ids) {
                add(id, null)
            }
        }

        fun addGenerated(message: ChatMessage) {
            val generated = requireNotNull(message.generated)
            var frame = 0
            for (id in generated.tokens) {
                if (id != Lfm3Omni.AUDIO_OUT_TOKEN) {
                    add(id, null)
                    continue
                }
                val start = frame * Lfm3Omni.NUM_CODEBOOKS
                val codes = generated.audioCodes.copyOfRange(start, start + Lfm3Omni.NUM_CODEBOOKS)
                val source = SlotSource.AudioOutCodes(codes)
                add(id, source)
                frame++
            }
        }

        fun addMedia(part: ContentPart) {
            when (part) {
                is ContentPart.Image -> {
                    val image = part.input
                    mediaKeys.add(image.key)
                    val grid = MediaGeometry.imageGrid(image.width, image.height, MediaLimits.MAX_IMAGE_TOKENS)
                    wrapped { rows(Lfm3Omni.IMAGE_TOKEN, image.key, FeatureStream.IMAGE, 0, grid.tokens) }
                }
                is ContentPart.Audio -> {
                    val audio = part.input
                    mediaKeys.add(audio.key)
                    val count = MediaGeometry.audioTokenCount(audio.sampleCount)
                    wrapped { rows(Lfm3Omni.AUDIO_TOKEN, audio.key, FeatureStream.AUDIO, 0, count) }
                }
                is ContentPart.Video -> {
                    mediaKeys.add(part.input.key)
                    addVideo(part.input, part.withSoundtrack)
                }
                is ContentPart.Text -> throw IllegalArgumentException("text is no media")
            }
        }

        fun checkpoints(): IntArray {
            val starts = tokens.indices.filter { position -> tokens[position] == Lfm3Omni.IM_START_TOKEN }
            val checkpoints = starts + tokens.size
            return checkpoints.toIntArray()
        }

        /** Lfm3OmniProcessor._video_expansion. */
        private fun addVideo(video: VideoInput, withSoundtrack: Boolean) {
            val indices = MediaGeometry.videoFrameIndices(video.frameCount, video.fps, MediaLimits.MAX_VIDEO_FRAMES)
            val size = MediaGeometry.videoResizeTarget(
                video.width,
                video.height,
                indices.size,
                MediaLimits.MAX_VIDEO_TOKENS,
            )
            val patchTokens = (size.height / 16) * (size.width / 16) / 4
            val timestamps = MediaGeometry.videoGroupTimestamps(indices, video.fps)
            val bounds = if (withSoundtrack && video.soundtrackSamples > 0) {
                val soundtrackTokens = MediaGeometry.audioTokenCount(video.soundtrackSamples)
                MediaGeometry.soundtrackBounds(indices, video.fps, soundtrackTokens)
            } else {
                null
            }
            wrapped {
                for ((index, timestamp) in timestamps.withIndex()) {
                    val timestampText = MediaGeometry.timestampText(timestamp)
                    val timestampIds = tokenizer.encode(timestampText)
                    addText(timestampIds)

                    val firstRow = index * patchTokens
                    wrapped { rows(Lfm3Omni.VIDEO_TOKEN, video.key, FeatureStream.VIDEO, firstRow, patchTokens) }
                    if (bounds != null) {
                        val count = bounds[index + 1] - bounds[index]
                        rows(Lfm3Omni.AUDIO_TOKEN, video.key, FeatureStream.AUDIO, bounds[index], count)
                    }
                }
            }
        }

        private fun wrapped(body: () -> Unit) {
            add(Lfm3Omni.VISION_START_TOKEN, null)
            body()
            add(Lfm3Omni.VISION_END_TOKEN, null)
        }

        private fun rows(placeholder: Int, key: String, stream: FeatureStream, first: Int, count: Int) {
            for (row in first until first + count) {
                val source = SlotSource.MediaRow(key, stream, row)
                add(placeholder, source)
            }
        }

        private fun add(id: Int, source: SlotSource?) {
            tokens.add(id)
            sources.add(source)
        }
    }

    // === Golden format to chat types ===

    private fun chatMessage(message: JsonObject): ChatMessage {
        val roleLabel = message.getValue("role").jsonPrimitive.content
        val role = ChatRole.entries.first { role -> role.label == roleLabel }
        val generated = message["generated"]?.let { generated -> generatedContent(generated.jsonObject) }

        val partArray = message["parts"]?.jsonArray ?: JsonArray(emptyList())
        val parts = partArray.map { part -> contentPart(part.jsonObject) }
        val callArray = message["tool_calls"]?.jsonArray ?: JsonArray(emptyList())
        val toolCalls = callArray.map { call -> toolCall(call.jsonObject) }
        return ChatMessage(role, parts, toolCalls, generated)
    }

    private fun generatedContent(generated: JsonObject): GeneratedContent {
        val tokens = Goldens.ids(generated["tokens"])
        val audioCodes = Goldens.ids(generated["audio_codes"])
        return GeneratedContent(tokens, audioCodes)
    }

    private fun toolCall(call: JsonObject): ToolCall {
        val name = call.getValue("name").jsonPrimitive.content
        val arguments = call.getValue("arguments").jsonObject
        return ToolCall(name, arguments)
    }

    private fun contentPart(part: JsonObject): ContentPart {
        fun int(name: String) = part.getValue(name).jsonPrimitive.int
        fun text(name: String) = part.getValue(name).jsonPrimitive.content

        return when (text("type")) {
            "text" -> ContentPart.Text(text("text"))
            "image" -> {
                val input = ImageInput(text("key"), int("width"), int("height")) { error("not loaded") }
                ContentPart.Image(input)
            }
            "audio" -> {
                val input = AudioInput(text("key"), int("sample_count")) { error("not loaded") }
                ContentPart.Audio(input)
            }
            "video" -> {
                val input = VideoInput(
                    key = text("key"),
                    width = int("width"),
                    height = int("height"),
                    frameCount = int("frame_count"),
                    fps = part.getValue("fps").jsonPrimitive.double,
                    soundtrackSamples = int("soundtrack_samples"),
                    loadFrames = { error("not loaded") },
                    loadSoundtrack = { error("not loaded") },
                )
                val withSoundtrack = part.getValue("with_soundtrack").jsonPrimitive.boolean
                ContentPart.Video(input, withSoundtrack)
            }
            else -> throw IllegalArgumentException("unknown part $part")
        }
    }

    private fun mediaPart(messages: List<ChatMessage>, key: String): ContentPart {
        val parts = messages.flatMap { message -> message.parts }
        return parts.first { part ->
            when (part) {
                is ContentPart.Image -> part.input.key == key
                is ContentPart.Audio -> part.input.key == key
                is ContentPart.Video -> part.input.key == key
                is ContentPart.Text -> false
            }
        }
    }

    private fun toolDefinition(tool: JsonObject) = ToolDefinition(
        name = tool.getValue("name").jsonPrimitive.content,
        description = tool.getValue("description").jsonPrimitive.content,
        parameters = tool.getValue("parameters").jsonObject,
    )
}
