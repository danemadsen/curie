package com.danemadsen.curie.data

import com.danemadsen.curie.data.BoundedJsonInputStream.Refusal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

private const val MEGABYTE = 1024L * 1024L

/**
 * Moves chats in and out of the app as JSON ([ChatDocument]): an export holds the visible thread
 * with images and audio embedded as data URIs; an import creates a new chat.
 *
 * kotlinx.serialization holds a whole document in memory, so both directions are bounded: an
 * export embeds at most [MAX_EMBEDDED_BYTES_PER_ATTACHMENT] per attachment and
 * [MAX_EMBEDDED_BYTES] in all, and an import refuses what passes its [ImportLimits]. An export
 * fits those limits unless the chat's text alone runs past 10 MiB.
 */
class ChatTransfer internal constructor(
    private val repository: ChatRepository,
    private val attachmentStore: AttachmentStore,
    private val clock: () -> Long,
    private val importLimits: ImportLimits,
) {
    constructor(repository: ChatRepository, attachmentStore: AttachmentStore) :
        this(repository, attachmentStore, System::currentTimeMillis, ImportLimits())

    /** Writes the chat's visible thread to [output] (left open); a failing output throws IOException. */
    @OptIn(ExperimentalSerializationApi::class)
    suspend fun exportChat(chatId: String, output: OutputStream): ChatExportResult = withContext(Dispatchers.IO) {
        val chat = repository.getChat(chatId)
        requireNotNull(chat) { "there is no chat $chatId" }
        val thread = repository.getThread(chatId)

        val budget = EmbeddingBudget()
        val messages = thread.map { threadMessage -> toDocument(threadMessage, budget) }
        val document = ChatDocument(
            format = ChatDocument.FORMAT,
            version = ChatDocument.VERSION,
            title = chat.title,
            createdAt = chat.createdAt,
            exportedAt = clock(),
            messages = messages,
        )

        json.encodeToStream(ChatDocument.serializer(), document, output)
        output.flush()
        ChatExportResult(budget.omittedAttachments)
    }

    /**
     * Reads a chat export from [input] (left open) into a new chat. Attachments are checked like
     * any other import, and damaged tool calls are dropped; both are counted in the result.
     * Throws [ChatImportException].
     */
    suspend fun importChat(input: InputStream): ChatImportResult = withContext(Dispatchers.IO) {
        val document = readDocument(input)

        val importedAttachments = mutableListOf<Attachment>()
        var skippedAttachments = 0
        var skippedToolCalls = 0
        try {
            val messages = mutableListOf<NewMessage>()
            for (messageDocument in document.messages) {
                val attachments = mutableListOf<Attachment>()
                for (attachmentDocument in messageDocument.attachments) {
                    val attachment = importAttachment(attachmentDocument)
                    if (attachment == null) {
                        skippedAttachments += 1
                        continue
                    }
                    attachments.add(attachment)
                    importedAttachments.add(attachment)
                }

                val toolCalls = messageDocument.toolCalls.filter { record -> isReplayable(record) }
                skippedToolCalls += messageDocument.toolCalls.size - toolCalls.size

                val message = NewMessage(
                    role = messageDocument.role,
                    text = messageDocument.text,
                    createdAt = messageDocument.createdAt,
                    toolCalls = toolCalls,
                    attachments = attachments,
                )
                messages.add(message)
            }

            val chat = repository.insertThread(document.title, document.createdAt, messages)
            ChatImportResult(chat, skippedAttachments, skippedToolCalls)
        } catch (error: Throwable) {
            // Nothing refers to the copies yet
            for (attachment in importedAttachments) {
                attachmentStore.deleteFile(File(attachment.path).name)
            }
            throw error
        }
    }

    private fun toDocument(threadMessage: ThreadMessage, budget: EmbeddingBudget): MessageDocument {
        val message = threadMessage.message
        val attachments = threadMessage.attachments.map { attachment -> toDocument(attachment, budget) }
        return MessageDocument(
            role = message.role,
            text = message.text,
            createdAt = message.createdAt,
            toolCalls = message.toolCalls,
            attachments = attachments,
        )
    }

    private fun toDocument(attachment: Attachment, budget: EmbeddingBudget): AttachmentDocument {
        if (attachment.kind == AttachmentKind.VIDEO) {
            return omitted(attachment, budget, "Videos are not exported.")
        }

        val file = File(attachment.path)
        if (!file.isFile) {
            return omitted(attachment, budget, "The file is missing.")
        }

        val size = file.length()
        val fitsAttachmentLimit = size <= MAX_EMBEDDED_BYTES_PER_ATTACHMENT
        val fitsTotalLimit = budget.embeddedBytes + size <= MAX_EMBEDDED_BYTES
        if (!fitsAttachmentLimit || !fitsTotalLimit) {
            return omitted(attachment, budget, "Too large to export.")
        }

        val content = file.readBytes()
        budget.embeddedBytes += content.size
        val data = DataUri.encode(attachment.mimeType, content)
        return AttachmentDocument(kind = attachment.kind, mimeType = attachment.mimeType, data = data)
    }

    private fun omitted(attachment: Attachment, budget: EmbeddingBudget, note: String): AttachmentDocument {
        budget.omittedAttachments += 1
        return AttachmentDocument(kind = attachment.kind, mimeType = attachment.mimeType, note = note)
    }

    @OptIn(ExperimentalSerializationApi::class)
    private fun readDocument(input: InputStream): ChatDocument {
        val boundedInput = BoundedJsonInputStream(
            source = input,
            maxDocumentBytes = importLimits.maxDocumentBytes,
            maxTokenBytes = importLimits.maxTokenBytes,
            maxDepth = importLimits.maxDepth,
        )
        val document = try {
            json.decodeFromStream(ChatDocument.serializer(), boundedInput)
        } catch (error: OutOfMemoryError) {
            // The limits keep a document well inside an app's heap, but the rest of the app may
            // already crowd it. Everything the parse allocated is garbage once it is gone.
            throw ChatImportException.TooLarge(importLimits.maxDocumentBytes)
        } catch (error: Exception) {
            throw readFailure(error, boundedInput)
        }

        if (document.format != ChatDocument.FORMAT || document.version > ChatDocument.VERSION) {
            throw ChatImportException.Unsupported(document.format, document.version)
        }
        return document
    }

    /**
     * The import error for [error]. kotlinx.serialization wraps what its input throws in its own
     * exceptions, so the stream says whether it refused to read on or its source failed.
     */
    private fun readFailure(error: Exception, input: BoundedJsonInputStream): Exception {
        val sourceFailure = input.sourceFailure
        return when {
            input.refusal == Refusal.DOCUMENT_TOO_LARGE -> ChatImportException.TooLarge(importLimits.maxDocumentBytes)
            input.refusal == Refusal.TOKEN_TOO_LARGE -> ChatImportException.TooLarge(importLimits.maxTokenBytes)
            // A chat export nests five levels deep
            input.refusal == Refusal.TOO_DEEP -> ChatImportException.Malformed(error)
            sourceFailure != null -> ChatImportException.Unreadable(sourceFailure)
            error is IOException -> ChatImportException.Unreadable(error)
            // SerializationException is an IllegalArgumentException
            error is IllegalArgumentException -> ChatImportException.Malformed(error)
            else -> error
        }
    }

    /** The stored copy, or null when the document left the file out or its content is unusable. */
    private suspend fun importAttachment(document: AttachmentDocument): Attachment? {
        val data = document.data ?: return null
        val dataUri = DataUri.parse(data) ?: return null

        return try {
            dataUri.openContent().use { content -> attachmentStore.importStream(content, dataUri.mimeType) }
        } catch (error: AttachmentImportException.StorageFailed) {
            // A full device fails every later attachment too: fail the import instead
            throw ChatImportException.StorageFailed(error)
        } catch (error: AttachmentImportException) {
            // Not media Curie reads, or not base64 (the decoder fails the copy as unreadable)
            null
        }
    }

    /** What the prompt builder needs to replay a call: a name, a JSON object of arguments and JSON results. */
    private fun isReplayable(record: ToolCallRecord): Boolean {
        if (record.name.isBlank()) {
            return false
        }
        val arguments = parseJsonOrNull(record.argumentsJson)
        if (arguments !is JsonObject) {
            return false
        }

        val result = record.resultJson ?: return true
        return parseJsonOrNull(result) != null
    }

    private fun parseJsonOrNull(text: String): JsonElement? {
        return try {
            Json.parseToJsonElement(text)
        } catch (error: SerializationException) {
            null
        }
    }

    private class EmbeddingBudget {
        var embeddedBytes = 0L
        var omittedAttachments = 0
    }

    internal companion object {
        /** Base64 makes the bytes a third larger, and both directions hold the whole document. */
        const val MAX_EMBEDDED_BYTES_PER_ATTACHMENT = 8 * MEGABYTE
        const val MAX_EMBEDDED_BYTES = 16 * MEGABYTE

        private val json = Json {
            encodeDefaults = true
            explicitNulls = false
            ignoreUnknownKeys = true
        }
    }
}

/**
 * What an import reads into memory at most (see [BoundedJsonInputStream]). Measured with compact
 * strings (as Android 14 and later have), the largest export and the worst document these limits
 * admit each parse within a 64 MiB heap.
 */
internal data class ImportLimits(
    /** The largest export embeds 16 MiB, 21.3 MiB of base64, which leaves over 10 MiB for text. */
    val maxDocumentBytes: Long = 32 * MEGABYTE,

    /** An attachment of 8 MiB is 11.2 MB of base64. */
    val maxTokenBytes: Long = 12 * MEGABYTE,

    /** A chat export nests five levels deep. */
    val maxDepth: Int = 64,
)
