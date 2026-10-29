package com.danemadsen.curie.inference

import com.danemadsen.curie.data.Attachment
import com.danemadsen.curie.data.AttachmentKind
import com.danemadsen.curie.data.MessageRole
import com.danemadsen.curie.data.ThreadMessage
import com.danemadsen.curie.data.ToolCallRecord
import com.danemadsen.curie.engine.chat.ChatMessage
import com.danemadsen.curie.engine.chat.ChatRole
import com.danemadsen.curie.engine.chat.ContentPart
import com.danemadsen.curie.engine.chat.GeneratedContent
import com.danemadsen.curie.engine.chat.ToolCall
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * A stored chat thread as the engine's messages: the system prompt, then each message.
 *
 * - User messages: their attachments in order (images, videos with their soundtrack, audio), then
 *   the text. An attachment whose file is gone is left out, with a note in its place.
 * - Assistant messages replay exactly what the engine generated (so the prompt matches the cache
 *   the turn left behind); those without (imported chats) give their text and tool calls.
 *   Empty ones, such as an answer that failed or was stopped before it said anything, are left out.
 * - Tool messages carry the JSON results of the calls before them.
 */
class ChatHistory(private val media: MediaInputFactory) {
    fun toChatMessages(
        systemPrompt: String,
        thread: List<ThreadMessage>,
        generated: Map<String, GeneratedContent>,
    ): List<ChatMessage> {
        val messages = ArrayList<ChatMessage>(thread.size + 1)
        messages.add(ChatMessage.system(systemPrompt))
        for (item in thread) {
            val message = toChatMessage(item, generated) ?: continue
            messages.add(message)
        }
        return messages
    }

    private fun toChatMessage(item: ThreadMessage, generated: Map<String, GeneratedContent>): ChatMessage? {
        val message = item.message
        return when (message.role) {
            MessageRole.USER -> userMessage(item)
            MessageRole.TOOL -> ChatMessage.tool(message.text)
            MessageRole.ASSISTANT -> {
                val content = generated[message.id]
                val hasContent = content != null && content.tokens.isNotEmpty()
                if (hasContent) {
                    ChatMessage(ChatRole.ASSISTANT, generated = content)
                } else {
                    writtenAssistantMessage(message.text, message.toolCalls)
                }
            }
        }
    }

    private fun userMessage(item: ThreadMessage): ChatMessage {
        val parts = ArrayList<ContentPart>()
        for (attachment in item.attachments) {
            val part = if (attachment.isAvailable) mediaPart(attachment) else unavailableNote(attachment)
            parts.add(part)
        }

        val text = item.message.text
        if (text.isNotBlank() || parts.isEmpty()) {
            parts.add(ContentPart.Text(text))
        }
        return ChatMessage(ChatRole.USER, parts)
    }

    private fun mediaPart(attachment: Attachment): ContentPart = when (attachment.kind) {
        AttachmentKind.IMAGE -> ContentPart.Image(media.image(attachment))
        AttachmentKind.VIDEO -> ContentPart.Video(media.video(attachment), withSoundtrack = attachment.hasSoundtrack)
        AttachmentKind.AUDIO -> ContentPart.Audio(media.audio(attachment))
    }

    private fun unavailableNote(attachment: Attachment): ContentPart {
        val kind = when (attachment.kind) {
            AttachmentKind.IMAGE -> "an image"
            AttachmentKind.VIDEO -> "a video"
            AttachmentKind.AUDIO -> "an audio clip"
        }
        return ContentPart.Text("[The user sent $kind here that is no longer available.]\n")
    }

    private fun writtenAssistantMessage(text: String, records: List<ToolCallRecord>): ChatMessage? {
        val toolCalls = records.mapNotNull { record -> toolCallOf(record) }
        if (text.isBlank() && toolCalls.isEmpty()) {
            return null
        }
        val parts = listOf(ContentPart.Text(text))
        return ChatMessage(ChatRole.ASSISTANT, parts, toolCalls)
    }

    private fun toolCallOf(record: ToolCallRecord): ToolCall? {
        val arguments = try {
            Json.parseToJsonElement(record.argumentsJson)
        } catch (error: SerializationException) {
            return null
        }
        if (arguments !is JsonObject) {
            return null
        }
        return ToolCall(record.name, arguments)
    }

    companion object {
        /**
         * True when a user message with these [attachments] was spoken: it holds a voice message
         * or another audio clip that is still there for the model to hear.
         */
        fun isSpoken(attachments: List<Attachment>): Boolean =
            attachments.any { attachment -> attachment.kind == AttachmentKind.AUDIO && attachment.isAvailable }
    }
}
