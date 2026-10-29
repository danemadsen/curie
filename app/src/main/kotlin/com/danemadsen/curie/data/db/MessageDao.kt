package com.danemadsen.curie.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

/**
 * The ids of the message :messageId and all its descendants. A recursive query has no depth
 * limit, unlike a cascading foreign key.
 */
internal const val SUBTREE_IDS = """
    WITH RECURSIVE subtree(id) AS (
        SELECT id FROM messages WHERE id = :messageId
        UNION ALL
        SELECT messages.id FROM messages JOIN subtree ON messages.parent_id = subtree.id
    )
    SELECT id FROM subtree
"""

private const val MESSAGE_ROW_COLUMNS = """
    id, chat_id, parent_id, selected_child_id, role, text, created_at, updated_at, tool_calls,
    speech_file, error
"""

@Dao
internal interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity)

    @Insert
    suspend fun insertAll(messages: List<MessageEntity>)

    @Query("SELECT $MESSAGE_ROW_COLUMNS FROM messages WHERE id = :messageId")
    suspend fun getRow(messageId: String): MessageRow?

    /** Oldest first: the order the thread numbers siblings in. */
    @Query(
        """
        SELECT id FROM messages
        WHERE chat_id = :chatId AND parent_id IS :parentId
        ORDER BY created_at, id
        """,
    )
    suspend fun getSiblingIds(chatId: String, parentId: String?): List<String>

    @Query("UPDATE messages SET selected_child_id = :childId WHERE id = :messageId")
    suspend fun selectChild(messageId: String, childId: String?)

    /** One UPDATE of one row; a null argument keeps the column's value. Returns the rows changed. */
    @Query(
        """
        UPDATE messages SET
            text = COALESCE(:text, text),
            generated_tokens = COALESCE(:generatedTokens, generated_tokens),
            generated_audio_codes = COALESCE(:generatedAudioCodes, generated_audio_codes),
            tool_calls = COALESCE(:toolCalls, tool_calls),
            speech_file = COALESCE(:speechFileName, speech_file),
            error = COALESCE(:error, error),
            updated_at = :updatedAt
        WHERE id = :messageId
        """,
    )
    suspend fun update(
        messageId: String,
        text: String?,
        generatedTokens: ByteArray?,
        generatedAudioCodes: ByteArray?,
        toolCalls: String?,
        speechFileName: String?,
        error: String?,
        updatedAt: Long,
    ): Int

    @Query(
        """
        SELECT id, generated_tokens, generated_audio_codes FROM messages
        WHERE id IN (:messageIds) AND generated_tokens IS NOT NULL
        """,
    )
    suspend fun getGenerated(messageIds: List<String>): List<GeneratedRow>

    @Query("SELECT speech_file FROM messages WHERE speech_file IS NOT NULL AND id IN ($SUBTREE_IDS)")
    suspend fun getSubtreeSpeechFileNames(messageId: String): List<String>

    @Query("SELECT speech_file FROM messages WHERE speech_file IS NOT NULL AND chat_id = :chatId")
    suspend fun getChatSpeechFileNames(chatId: String): List<String>

    @Query("SELECT speech_file FROM messages WHERE speech_file IS NOT NULL")
    suspend fun getAllSpeechFileNames(): List<String>

    @Query("SELECT COUNT(*) FROM messages WHERE speech_file = :speechFileName")
    suspend fun countSpeechFileReferences(speechFileName: String): Int

    @Query("DELETE FROM messages WHERE id IN ($SUBTREE_IDS)")
    suspend fun deleteSubtree(messageId: String)
}
