package com.danemadsen.curie.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
internal interface AttachmentDao {
    @Insert
    suspend fun insertAll(attachments: List<AttachmentEntity>)

    @Query("SELECT DISTINCT file_name FROM attachments WHERE message_id IN ($SUBTREE_IDS)")
    suspend fun getSubtreeFileNames(messageId: String): List<String>

    @Query(
        """
        SELECT DISTINCT attachments.file_name FROM attachments
        JOIN messages ON attachments.message_id = messages.id
        WHERE messages.chat_id = :chatId
        """,
    )
    suspend fun getChatFileNames(chatId: String): List<String>

    @Query("SELECT DISTINCT file_name FROM attachments")
    suspend fun getAllFileNames(): List<String>

    @Query("SELECT COUNT(*) FROM attachments WHERE file_name = :fileName")
    suspend fun countReferences(fileName: String): Int
}
