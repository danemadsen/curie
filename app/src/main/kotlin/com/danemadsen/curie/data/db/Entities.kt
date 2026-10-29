package com.danemadsen.curie.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.danemadsen.curie.data.AttachmentKind
import com.danemadsen.curie.data.MessageRole

/** A chat. It is the parent of its first messages: [selectedChildId] picks the visible one. */
@Entity(
    tableName = "chats",
    indices = [Index("updated_at")],
)
internal data class ChatEntity(
    @PrimaryKey
    val id: String,

    val title: String,

    @ColumnInfo(name = "selected_child_id")
    val selectedChildId: String?,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

/**
 * A message. parent_id has no foreign key on purpose: a cascade through a deep thread would hit
 * SQLite's trigger depth limit (1000), so subtrees are deleted with a recursive query instead.
 * The generated content is two little-endian int32 BLOBs (see [IntArrayBlob]).
 */
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("chat_id", "parent_id"),
        Index("parent_id"),
    ],
)
internal class MessageEntity(
    @PrimaryKey
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

    @ColumnInfo(name = "generated_tokens")
    val generatedTokens: ByteArray?,

    @ColumnInfo(name = "generated_audio_codes")
    val generatedAudioCodes: ByteArray?,

    /** A JSON array of ToolCallRecord. */
    @ColumnInfo(name = "tool_calls")
    val toolCalls: String,

    /** The WAV of the spoken answer, by name in the speech directory (see [AttachmentEntity.fileName]). */
    @ColumnInfo(name = "speech_file")
    val speechFileName: String?,

    val error: String?,
)

/**
 * An attachment of a message. Sibling messages (an edit) may share a file, so the key is the
 * pair and a file is deleted only when no row names it any more.
 */
@Entity(
    tableName = "attachments",
    primaryKeys = ["message_id", "id"],
    foreignKeys = [
        ForeignKey(
            entity = MessageEntity::class,
            parentColumns = ["id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("file_name")],
)
internal data class AttachmentEntity(
    @ColumnInfo(name = "message_id")
    val messageId: String,

    val id: String,
    val position: Int,
    val kind: AttachmentKind,

    /** Relative to the attachment directory, so it resolves wherever the app's files move. */
    @ColumnInfo(name = "file_name")
    val fileName: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    val width: Int,
    val height: Int,

    @ColumnInfo(name = "duration_ms")
    val durationMs: Long,

    @ColumnInfo(name = "frame_count")
    val frameCount: Int,

    val fps: Double,

    @ColumnInfo(name = "sample_count_16k")
    val sampleCount16k: Int,
)
