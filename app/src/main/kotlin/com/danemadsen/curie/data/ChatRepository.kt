package com.danemadsen.curie.data

import android.content.Context
import androidx.room.withTransaction
import com.danemadsen.curie.data.db.ChatEntity
import com.danemadsen.curie.data.db.ChatTree
import com.danemadsen.curie.data.db.IntArrayBlob
import com.danemadsen.curie.data.db.MessageEntity
import com.danemadsen.curie.data.db.MessageWithAttachments
import com.danemadsen.curie.engine.chat.GeneratedContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Chats as message trees, the single source of truth for them. Every message has a parent (null
 * for the first messages of a chat) and a selected child; the visible thread follows the
 * selections from the chat's selected first message (see [observeThread]). Regenerating an answer
 * or editing a message adds a sibling ([addSibling]) instead of overwriting, and the sibling
 * controls move between them ([selectSibling]).
 *
 * Files follow their rows: deleting messages deletes the attachments no other message uses and
 * the messages' speech files. Rows name their files relative to their directories, and a file
 * that is gone (a restore from a cloud backup leaves media out) shows as unavailable
 * ([Attachment.isAvailable], a null [Message.speechFile]) instead of failing.
 */
class ChatRepository internal constructor(
    private val database: CurieDatabase,
    private val attachmentStore: AttachmentStore,

    /** Where the WAVs of spoken answers belong ([speechFileFor]); they are deleted with their messages. */
    val speechDirectory: File,

    private val clock: () -> Long,
) {
    constructor(context: Context, database: CurieDatabase, attachmentStore: AttachmentStore) : this(
        database = database,
        attachmentStore = attachmentStore,
        speechDirectory = File(context.filesDir, SPEECH_DIRECTORY_NAME),
        clock = System::currentTimeMillis,
    )

    private val chatDao = database.chatDao()
    private val messageDao = database.messageDao()
    private val attachmentDao = database.attachmentDao()

    /** Timestamps strictly increase, so siblings created in the same millisecond keep their order. */
    private val lastTimestamp = AtomicLong()

    // === Chats ===

    /** Every chat, the one with the newest message first. */
    fun observeChats(): Flow<List<Chat>> {
        val entities = chatDao.observeAll()
        return entities.map { chats -> chats.map { chat -> chat.toChat() } }
    }

    /** The chat, or null once it is deleted. */
    fun observeChat(chatId: String): Flow<Chat?> {
        val entity = chatDao.observe(chatId)
        return entity.map { chat -> chat?.toChat() }
    }

    suspend fun getChat(chatId: String): Chat? = chatDao.get(chatId)?.toChat()

    /**
     * An empty chat. A blank [title] is filled in from the first user message; create the chat when
     * the first message is sent, so abandoned "new chats" do not pile up.
     */
    suspend fun createChat(title: String = ""): Chat {
        val now = nextTimestamp()
        val chat = ChatEntity(
            id = newId(),
            title = title.trim(),
            selectedChildId = null,
            createdAt = now,
            updatedAt = now,
        )
        chatDao.insert(chat)
        return chat.toChat()
    }

    /** Renames without reordering the chat list. */
    suspend fun renameChat(chatId: String, title: String) {
        chatDao.updateTitle(chatId, title.trim())
    }

    suspend fun deleteChat(chatId: String) {
        val removedFiles = database.withTransaction {
            val attachmentFiles = attachmentDao.getChatFileNames(chatId)
            val speechFiles = messageDao.getChatSpeechFileNames(chatId)

            // Messages and attachment rows cascade
            chatDao.delete(chatId)
            unreferencedFiles(attachmentFiles, speechFiles)
        }
        deleteFiles(removedFiles)
    }

    suspend fun deleteAllChats() {
        val removedFiles = database.withTransaction {
            val attachmentFiles = attachmentDao.getAllFileNames()
            val speechFiles = messageDao.getAllSpeechFileNames()

            chatDao.deleteAll()
            unreferencedFiles(attachmentFiles, speechFiles)
        }
        deleteFiles(removedFiles)
    }

    // === The visible thread ===

    /**
     * The visible thread of the chat, re-emitted when it changes; empty when the chat does not
     * exist. Generated-content BLOBs are left out ([loadGeneratedContent]), but every
     * [updateMessage] re-runs the query, so streaming writers should throttle their updates.
     */
    fun observeThread(chatId: String): Flow<List<ThreadMessage>> {
        val trees = chatDao.observeTree(chatId)
        val threads = trees.map { tree -> toThread(tree) }
        val changedThreads = threads.distinctUntilChanged()
        // Mapping checks that the messages' files exist
        return changedThreads.flowOn(Dispatchers.IO)
    }

    /** The visible thread as it is now, for building a prompt. */
    suspend fun getThread(chatId: String): List<ThreadMessage> {
        val tree = chatDao.getTree(chatId)
        return withContext(Dispatchers.IO) { toThread(tree) }
    }

    suspend fun getMessage(messageId: String): Message? {
        val row = messageDao.getRow(messageId) ?: return null
        return withContext(Dispatchers.IO) { row.toMessage(speechDirectory) }
    }

    /**
     * The engine's record of what the assistant messages among [messageIds] fed the decoder, for
     * replaying them into a prompt. Messages without generated content are left out of the map.
     */
    suspend fun loadGeneratedContent(messageIds: Collection<String>): Map<String, GeneratedContent> {
        val contents = HashMap<String, GeneratedContent>()
        val distinctIds = messageIds.distinct()

        // SQLite before 3.32 (Android 10) accepts at most 999 arguments per statement
        for (chunk in distinctIds.chunked(MAX_QUERY_ARGUMENTS)) {
            val rows = messageDao.getGenerated(chunk)
            for (row in rows) {
                val content = row.toGeneratedContent() ?: continue
                contents[row.id] = content
            }
        }
        return contents
    }

    // === Messages ===

    /**
     * Adds a message under [parentId] (null: a first message of the chat) and selects it, so it
     * continues the visible thread from its parent. The [attachments] must come from the
     * [AttachmentStore]; they are shown in order. The first user message with text names an
     * untitled chat.
     */
    suspend fun appendMessage(
        chatId: String,
        parentId: String?,
        role: MessageRole,
        text: String,
        attachments: List<Attachment> = emptyList(),
        toolCalls: List<ToolCallRecord> = emptyList(),
        generated: GeneratedContent? = null,
    ): Message {
        for (attachment in attachments) {
            require(attachmentStore.contains(attachment)) { "${attachment.path} is not in the attachment store" }
        }
        val generatedTokens = generated?.let { content -> IntArrayBlob.encode(content.tokens) }
        val generatedAudioCodes = generated?.let { content -> IntArrayBlob.encode(content.audioCodes) }
        val toolCallsJson = encodeToolCalls(toolCalls)

        return database.withTransaction {
            val chat = chatDao.get(chatId)
            requireNotNull(chat) { "there is no chat $chatId" }
            if (parentId != null) {
                val parent = messageDao.getRow(parentId)
                require(parent?.chatId == chatId) { "chat $chatId has no message $parentId" }
            }

            val now = nextTimestamp()
            val message = MessageEntity(
                id = newId(),
                chatId = chatId,
                parentId = parentId,
                selectedChildId = null,
                role = role,
                text = text,
                createdAt = now,
                updatedAt = now,
                generatedTokens = generatedTokens,
                generatedAudioCodes = generatedAudioCodes,
                toolCalls = toolCallsJson,
                speechFileName = null,
                error = null,
            )
            messageDao.insert(message)

            val attachmentRows = attachments.mapIndexed { position, attachment ->
                attachment.toEntity(message.id, position)
            }
            attachmentDao.insertAll(attachmentRows)

            selectChild(chatId, parentId, message.id)
            chatDao.updateTimestamp(chatId, now)

            val namesChat = chat.title.isBlank() && role == MessageRole.USER
            val title = if (namesChat) titleFrom(text) else ""
            if (title.isNotEmpty()) {
                chatDao.updateTitle(chatId, title)
            }

            message.toMessage(speechDirectory)
        }
    }

    /**
     * Changes the given fields of a message (null leaves a field as it is) with one UPDATE of one
     * row. Every observer of the chat's thread re-reads it, so a caller streaming an answer should
     * write at most every ~100 ms and once more when it ends. Returns false when the message no
     * longer exists (deleted while it was being generated), which a streaming caller can treat as
     * a cancellation.
     *
     * [speechFile] must be a file in [speechDirectory], such as [speechFileFor] the message.
     */
    suspend fun updateMessage(
        messageId: String,
        text: String? = null,
        generated: GeneratedContent? = null,
        toolCalls: List<ToolCallRecord>? = null,
        speechFile: String? = null,
        error: String? = null,
    ): Boolean {
        val generatedTokens = generated?.let { content -> IntArrayBlob.encode(content.tokens) }
        val generatedAudioCodes = generated?.let { content -> IntArrayBlob.encode(content.audioCodes) }
        val toolCallsJson = toolCalls?.let { calls -> encodeToolCalls(calls) }
        val speechFileName = speechFile?.let { path -> speechFileNameOf(path) }

        val changedRows = messageDao.update(
            messageId = messageId,
            text = text,
            generatedTokens = generatedTokens,
            generatedAudioCodes = generatedAudioCodes,
            toolCalls = toolCallsJson,
            speechFileName = speechFileName,
            error = error,
            updatedAt = nextTimestamp(),
        )
        return changedRows > 0
    }

    /**
     * Adds a sibling of [messageId] (same parent and role) and selects it: regenerating an answer
     * (an empty assistant sibling to stream into) or editing a message (pass the attachments the
     * edit keeps; they may be the original's).
     */
    suspend fun addSibling(
        messageId: String,
        text: String,
        attachments: List<Attachment> = emptyList(),
        toolCalls: List<ToolCallRecord> = emptyList(),
    ): Message = database.withTransaction {
        val original = messageDao.getRow(messageId)
        requireNotNull(original) { "there is no message $messageId" }

        appendMessage(
            chatId = original.chatId,
            parentId = original.parentId,
            role = original.role,
            text = text,
            attachments = attachments,
            toolCalls = toolCalls,
        )
    }

    /**
     * Shows the sibling [offset] places from [messageId] (-1: the previous one, 1: the next one);
     * nothing happens past the first or last sibling.
     */
    suspend fun selectSibling(messageId: String, offset: Int) {
        database.withTransaction {
            val message = messageDao.getRow(messageId) ?: return@withTransaction
            val siblings = messageDao.getSiblingIds(message.chatId, message.parentId)
            val index = siblings.indexOf(messageId)
            val target = siblings.getOrNull(index + offset) ?: return@withTransaction

            selectChild(message.chatId, message.parentId, target)
        }
    }

    /**
     * Deletes the message and everything after it in every branch. When it was the visible one, its
     * parent shows the sibling before it (else the one after it, else nothing).
     */
    suspend fun deleteMessage(messageId: String) {
        val removedFiles = database.withTransaction {
            val message = messageDao.getRow(messageId) ?: return@withTransaction null
            val siblings = messageDao.getSiblingIds(message.chatId, message.parentId)
            val wasSelected = selectedChildOf(message.chatId, message.parentId) == messageId

            val attachmentFiles = attachmentDao.getSubtreeFileNames(messageId)
            val speechFiles = messageDao.getSubtreeSpeechFileNames(messageId)
            messageDao.deleteSubtree(messageId)

            if (wasSelected) {
                val index = siblings.indexOf(messageId)
                val replacement = siblings.getOrNull(index - 1) ?: siblings.getOrNull(index + 1)
                selectChild(message.chatId, message.parentId, replacement)
            }
            unreferencedFiles(attachmentFiles, speechFiles)
        }
        if (removedFiles != null) {
            deleteFiles(removedFiles)
        }
    }

    // === Files ===

    /** Where to write the WAV of [messageId]'s spoken answer before storing it with [updateMessage]. */
    fun speechFileFor(messageId: String): File = File(speechDirectory, "$messageId.wav")

    /**
     * Deletes the file of an attachment that was imported but not sent (removed from a draft); a
     * file a message uses (an edit starts with the original's attachments) is kept.
     */
    suspend fun discardAttachment(attachment: Attachment) {
        if (!attachmentStore.contains(attachment)) {
            return
        }

        val fileName = File(attachment.path).name
        val references = attachmentDao.countReferences(fileName)
        if (references == 0) {
            withContext(Dispatchers.IO) { attachmentStore.deleteFile(fileName) }
        }
    }

    /**
     * Deletes attachment and speech files no message names that are older than [minimumAgeMs]
     * (leftovers of a crash or of drafts lost with the process). Call it once at startup: the age
     * spares files a draft or a generation is about to use.
     */
    suspend fun deleteOrphanedFiles(minimumAgeMs: Long = ORPHAN_MINIMUM_AGE_MS) {
        val referencedAttachments = attachmentDao.getAllFileNames().toHashSet()
        val referencedSpeech = messageDao.getAllSpeechFileNames().toHashSet()
        val cutoff = clock() - minimumAgeMs

        withContext(Dispatchers.IO) {
            attachmentStore.deleteUnreferencedFiles(referencedAttachments, cutoff)

            val speechFiles = speechDirectory.listFiles() ?: return@withContext
            for (file in speechFiles) {
                val unreferenced = file.name !in referencedSpeech
                if (unreferenced && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        }
    }

    // === Import ===

    /** Creates a chat holding [messages] as one linear thread, atomically (the chat import). */
    internal suspend fun insertThread(title: String, createdAt: Long, messages: List<NewMessage>): Chat {
        for (message in messages) {
            for (attachment in message.attachments) {
                require(attachmentStore.contains(attachment)) { "${attachment.path} is not in the attachment store" }
            }
        }

        val messageIds = messages.map { newId() }
        val chat = ChatEntity(
            id = newId(),
            title = title.trim(),
            selectedChildId = messageIds.firstOrNull(),
            createdAt = createdAt,
            updatedAt = nextTimestamp(),
        )
        val messageRows = messages.mapIndexed { index, message ->
            MessageEntity(
                id = messageIds[index],
                chatId = chat.id,
                parentId = messageIds.getOrNull(index - 1),
                selectedChildId = messageIds.getOrNull(index + 1),
                role = message.role,
                text = message.text,
                createdAt = message.createdAt,
                updatedAt = message.createdAt,
                generatedTokens = null,
                generatedAudioCodes = null,
                toolCalls = encodeToolCalls(message.toolCalls),
                speechFileName = null,
                error = null,
            )
        }
        val attachmentRows = messages.flatMapIndexed { index, message ->
            message.attachments.mapIndexed { position, attachment -> attachment.toEntity(messageIds[index], position) }
        }

        database.withTransaction {
            chatDao.insert(chat)
            messageDao.insertAll(messageRows)
            attachmentDao.insertAll(attachmentRows)
        }
        return chat.toChat()
    }

    // === Helpers ===

    private fun toThread(tree: ChatTree?): List<ThreadMessage> {
        if (tree == null) {
            return emptyList()
        }
        val nodes = tree.messages.map { entry -> toTreeNode(entry) }
        return visibleThread(tree.chat.selectedChildId, nodes)
    }

    private fun toTreeNode(entry: MessageWithAttachments): TreeNode {
        val orderedAttachments = entry.attachments.sortedBy { attachment -> attachment.position }
        val attachments = orderedAttachments.map { attachment -> attachment.toAttachment(attachmentStore.directory) }
        val message = entry.message.toMessage(speechDirectory)
        return TreeNode(message, attachments)
    }

    /** Speech files are stored by name, so they resolve wherever the app's files move. */
    private fun speechFileNameOf(path: String): String {
        val file = File(path)
        require(file.parentFile == speechDirectory) { "$path is not in the speech directory $speechDirectory" }
        return file.name
    }

    /** The selected child of [parentId], where a null parent means the chat itself. */
    private suspend fun selectedChildOf(chatId: String, parentId: String?): String? {
        if (parentId == null) {
            return chatDao.get(chatId)?.selectedChildId
        }
        return messageDao.getRow(parentId)?.selectedChildId
    }

    private suspend fun selectChild(chatId: String, parentId: String?, childId: String?) {
        if (parentId == null) {
            chatDao.selectFirstMessage(chatId, childId)
        } else {
            messageDao.selectChild(parentId, childId)
        }
    }

    /** Of the files the deleted rows named, those no remaining row names. Call inside the transaction. */
    private suspend fun unreferencedFiles(attachmentFiles: List<String>, speechFiles: List<String>): RemovedFiles {
        val attachments = attachmentFiles.filter { fileName -> attachmentDao.countReferences(fileName) == 0 }
        val distinctSpeechFiles = speechFiles.distinct()
        val speech = distinctSpeechFiles.filter { fileName -> messageDao.countSpeechFileReferences(fileName) == 0 }
        return RemovedFiles(attachments, speech)
    }

    /** After the transaction committed: a failed delete leaves an orphan, never a dangling row. */
    private suspend fun deleteFiles(files: RemovedFiles) = withContext(Dispatchers.IO) {
        for (fileName in files.attachmentFileNames) {
            attachmentStore.deleteFile(fileName)
        }

        for (fileName in files.speechFileNames) {
            deleteFileNamed(speechDirectory, fileName)
        }
    }

    private fun nextTimestamp(): Long {
        val now = clock()
        return lastTimestamp.updateAndGet { last -> max(now, last + 1) }
    }

    private fun newId(): String = UUID.randomUUID().toString()

    private class RemovedFiles(
        val attachmentFileNames: List<String>,
        val speechFileNames: List<String>,
    )

    companion object {
        private const val SPEECH_DIRECTORY_NAME = "speech"
        private const val MAX_QUERY_ARGUMENTS = 500
        private const val MAX_TITLE_LENGTH = 40
        private const val ELLIPSIS = "…"
        private const val ORPHAN_MINIMUM_AGE_MS = 24L * 60 * 60 * 1000

        /**
         * The first non-blank line of [text]; a longer one is cut to [MAX_TITLE_LENGTH] characters,
         * ending in an ellipsis.
         */
        internal fun titleFrom(text: String): String {
            val lines = text.lineSequence()
            val trimmedLines = lines.map { line -> line.trim() }
            val firstLine = trimmedLines.firstOrNull { line -> line.isNotEmpty() } ?: return ""
            if (firstLine.length <= MAX_TITLE_LENGTH) {
                return firstLine
            }

            // Room for the ellipsis, never splitting a surrogate pair (an emoji) in half
            var end = MAX_TITLE_LENGTH - ELLIPSIS.length
            if (Character.isHighSurrogate(firstLine[end - 1])) {
                end -= 1
            }
            val cut = firstLine.substring(0, end)
            return cut.trimEnd() + ELLIPSIS
        }
    }
}

/** A message of an imported thread. */
internal class NewMessage(
    val role: MessageRole,
    val text: String,
    val createdAt: Long,
    val toolCalls: List<ToolCallRecord>,
    val attachments: List<Attachment>,
)
