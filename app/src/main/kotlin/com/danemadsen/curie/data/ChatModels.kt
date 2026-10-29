package com.danemadsen.curie.data

import com.danemadsen.curie.engine.chat.ChatRole
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A conversation. A blank [title] means untitled (the UI shows its own placeholder); the first
 * user message names an untitled chat. [updatedAt] is when a message was last added.
 */
data class Chat(
    val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Who wrote a message. The serial names are the chat export's role names. */
@Serializable
enum class MessageRole(val chatRole: ChatRole) {
    @SerialName("user")
    USER(ChatRole.USER),

    @SerialName("assistant")
    ASSISTANT(ChatRole.ASSISTANT),

    @SerialName("tool")
    TOOL(ChatRole.TOOL),
}

/**
 * A tool call an assistant message made. [argumentsJson] and [resultJson] are JSON text;
 * [resultJson] stays null until the tool has run. [summary] is a short line for the UI.
 */
@Serializable
data class ToolCallRecord(
    val name: String,
    val argumentsJson: String,
    val resultJson: String? = null,
    val summary: String? = null,
)

/**
 * A node of a chat's message tree. Siblings share [parentId] (null for the first messages of a
 * chat) and [selectedChildId] names the child the visible thread continues with.
 *
 * The engine's GeneratedContent of an assistant message is stored with the message but read
 * separately ([ChatRepository.loadGeneratedContent]): the thread flows re-run on every streaming
 * update, so they leave those BLOBs out.
 */
data class Message(
    val id: String,
    val chatId: String,
    val parentId: String?,
    val selectedChildId: String?,
    val role: MessageRole,
    val text: String,
    val createdAt: Long,
    val updatedAt: Long,
    val toolCalls: List<ToolCallRecord>,

    /**
     * The WAV of the spoken answer ([ChatRepository.speechFileFor] this message); null when there
     * is none, or its file is gone (a cloud backup leaves speech out): synthesise it again then.
     */
    val speechFile: String?,

    /** Why producing the message failed, for the UI; null when it did not fail. */
    val error: String?,
)

/**
 * A message of the visible thread with its attachments (in order) and its place among its
 * siblings: [siblingIndex] counts from 0, oldest first.
 */
data class ThreadMessage(
    val message: Message,
    val attachments: List<Attachment>,
    val siblingIndex: Int,
    val siblingCount: Int,
)
