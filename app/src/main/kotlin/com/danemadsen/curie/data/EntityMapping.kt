package com.danemadsen.curie.data

import com.danemadsen.curie.data.db.AttachmentEntity
import com.danemadsen.curie.data.db.ChatEntity
import com.danemadsen.curie.data.db.GeneratedRow
import com.danemadsen.curie.data.db.IntArrayBlob
import com.danemadsen.curie.data.db.MessageEntity
import com.danemadsen.curie.data.db.MessageRow
import com.danemadsen.curie.engine.chat.GeneratedContent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

private val storageJson = Json { ignoreUnknownKeys = true }
private val toolCallListSerializer = ListSerializer(ToolCallRecord.serializer())

internal fun encodeToolCalls(toolCalls: List<ToolCallRecord>): String =
    storageJson.encodeToString(toolCallListSerializer, toolCalls)

internal fun decodeToolCalls(json: String): List<ToolCallRecord> =
    storageJson.decodeFromString(toolCallListSerializer, json)

internal fun ChatEntity.toChat() = Chat(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

/** [speechDirectory] holds the speech files, which messages name relative to it. */
internal fun MessageRow.toMessage(speechDirectory: File) = Message(
    id = id,
    chatId = chatId,
    parentId = parentId,
    selectedChildId = selectedChildId,
    role = role,
    text = text,
    createdAt = createdAt,
    updatedAt = updatedAt,
    toolCalls = decodeToolCalls(toolCalls),
    speechFile = existingFilePath(speechDirectory, speechFileName),
    error = error,
)

/** [speechDirectory] holds the speech files, which messages name relative to it. */
internal fun MessageEntity.toMessage(speechDirectory: File) = Message(
    id = id,
    chatId = chatId,
    parentId = parentId,
    selectedChildId = selectedChildId,
    role = role,
    text = text,
    createdAt = createdAt,
    updatedAt = updatedAt,
    toolCalls = decodeToolCalls(toolCalls),
    speechFile = existingFilePath(speechDirectory, speechFileName),
    error = error,
)

/** [directory] holds the attachment files, which rows name relative to it. */
internal fun AttachmentEntity.toAttachment(directory: File): Attachment {
    val file = File(directory, fileName)
    return Attachment(
        id = id,
        kind = kind,
        path = file.path,
        mimeType = mimeType,
        width = width,
        height = height,
        durationMs = durationMs,
        frameCount = frameCount,
        fps = fps,
        sampleCount16k = sampleCount16k,
        isAvailable = file.isFile,
    )
}

internal fun Attachment.toEntity(messageId: String, position: Int): AttachmentEntity {
    val file = File(path)
    return AttachmentEntity(
        messageId = messageId,
        id = id,
        position = position,
        kind = kind,
        fileName = file.name,
        mimeType = mimeType,
        width = width,
        height = height,
        durationMs = durationMs,
        frameCount = frameCount,
        fps = fps,
        sampleCount16k = sampleCount16k,
    )
}

/** Null when the row holds no complete generated content. */
internal fun GeneratedRow.toGeneratedContent(): GeneratedContent? {
    val tokens = generatedTokens ?: return null
    val audioCodes = generatedAudioCodes ?: return null
    return GeneratedContent(IntArrayBlob.decode(tokens), IntArrayBlob.decode(audioCodes))
}

/** The path of the file [fileName] in [directory], or null when there is no name or no such file. */
private fun existingFilePath(directory: File, fileName: String?): String? {
    if (fileName == null || !isPlainFileName(fileName)) {
        return null
    }
    val file = File(directory, fileName)
    return if (file.isFile) file.path else null
}
