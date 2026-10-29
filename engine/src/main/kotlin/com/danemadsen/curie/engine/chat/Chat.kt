package com.danemadsen.curie.engine.chat

import com.danemadsen.curie.engine.prompt.AudioInput
import com.danemadsen.curie.engine.prompt.ImageInput
import com.danemadsen.curie.engine.prompt.VideoInput
import kotlinx.serialization.json.JsonObject

enum class ChatRole(val label: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}

/** One part of a message, rendered in order. */
sealed interface ContentPart {
    data class Text(val text: String) : ContentPart

    data class Image(val input: ImageInput) : ContentPart

    /** A video; with [withSoundtrack] its audio is interleaved after each frame group. */
    data class Video(val input: VideoInput, val withSoundtrack: Boolean) : ContentPart

    data class Audio(val input: AudioInput) : ContentPart
}

/**
 * Exactly what an assistant turn fed the decoder after "<|im_start|>assistant\n<think></think>":
 * every token id, with the 12 codes of each <|audio_out|> frame in order. Replaying it makes the
 * history match the cache the turn left behind.
 */
class GeneratedContent(
    val tokens: IntArray,
    val audioCodes: IntArray,
) {
    init {
        require(audioCodes.size % 12 == 0) { "audio codes come in frames of 12" }
    }
}

data class ToolCall(
    val name: String,
    val arguments: JsonObject,
)

/** A function the model may call; [parameters] is its JSON schema. */
data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

/**
 * A chat message. An assistant message the engine generated carries [generated], which replaces
 * [parts] and [toolCalls] when the history is rendered.
 */
data class ChatMessage(
    val role: ChatRole,
    val parts: List<ContentPart> = emptyList(),
    val toolCalls: List<ToolCall> = emptyList(),
    val generated: GeneratedContent? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage(ChatRole.SYSTEM, listOf(ContentPart.Text(text)))

        fun user(text: String) = ChatMessage(ChatRole.USER, listOf(ContentPart.Text(text)))

        fun tool(result: String) = ChatMessage(ChatRole.TOOL, listOf(ContentPart.Text(result)))
    }
}
