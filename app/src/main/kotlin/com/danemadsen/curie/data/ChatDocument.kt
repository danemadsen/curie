package com.danemadsen.curie.data

import kotlinx.serialization.Serializable

/**
 * The chat export: the visible thread of one chat. Attachments travel as base64 data URIs;
 * videos (and files too large to embed) are listed without data and with a [AttachmentDocument.note].
 * The engine's generated content and speech files are not exported.
 */
@Serializable
internal data class ChatDocument(
    val format: String,
    val version: Int,
    val title: String = "",
    val createdAt: Long = 0,
    val exportedAt: Long = 0,
    val messages: List<MessageDocument> = emptyList(),
) {
    companion object {
        const val FORMAT = "curie-chat"
        const val VERSION = 1
    }
}

@Serializable
internal data class MessageDocument(
    val role: MessageRole,
    val text: String = "",
    val createdAt: Long = 0,
    val toolCalls: List<ToolCallRecord> = emptyList(),
    val attachments: List<AttachmentDocument> = emptyList(),
)

@Serializable
internal data class AttachmentDocument(
    val kind: AttachmentKind,
    val mimeType: String,

    /** "data:<mime type>;base64,<content>", or null when the file was left out. */
    val data: String? = null,

    /** Why the file was left out. */
    val note: String? = null,
)
