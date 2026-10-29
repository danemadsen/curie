package com.danemadsen.curie.data.db

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Relation
import com.danemadsen.curie.data.MessageRole

/** A message without its generated-content BLOBs: what the thread queries read. */
internal data class MessageRow(
    val id: String,

    @ColumnInfo(name = "chat_id")
    val chatId: String,

    @ColumnInfo(name = "parent_id")
    val parentId: String?,

    @ColumnInfo(name = "selected_child_id")
    val selectedChildId: String?,

    val role: MessageRole,
    val text: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    @ColumnInfo(name = "tool_calls")
    val toolCalls: String,

    @ColumnInfo(name = "speech_file")
    val speechFileName: String?,

    val error: String?,
)

internal data class MessageWithAttachments(
    @Embedded
    val message: MessageRow,

    @Relation(parentColumn = "id", entityColumn = "message_id")
    val attachments: List<AttachmentEntity>,
)

/** A chat with its whole message tree, read in one transaction. */
internal data class ChatTree(
    @Embedded
    val chat: ChatEntity,

    @Relation(entity = MessageEntity::class, parentColumn = "id", entityColumn = "chat_id")
    val messages: List<MessageWithAttachments>,
)

internal class GeneratedRow(
    val id: String,

    @ColumnInfo(name = "generated_tokens")
    val generatedTokens: ByteArray?,

    @ColumnInfo(name = "generated_audio_codes")
    val generatedAudioCodes: ByteArray?,
)
