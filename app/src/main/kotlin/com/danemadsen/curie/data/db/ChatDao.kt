package com.danemadsen.curie.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
internal interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY updated_at DESC, id")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun observe(chatId: String): Flow<ChatEntity?>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun get(chatId: String): ChatEntity?

    @Transaction
    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun observeTree(chatId: String): Flow<ChatTree?>

    @Transaction
    @Query("SELECT * FROM chats WHERE id = :chatId")
    suspend fun getTree(chatId: String): ChatTree?

    @Insert
    suspend fun insert(chat: ChatEntity)

    @Query("UPDATE chats SET title = :title WHERE id = :chatId")
    suspend fun updateTitle(chatId: String, title: String): Int

    @Query("UPDATE chats SET updated_at = :updatedAt WHERE id = :chatId")
    suspend fun updateTimestamp(chatId: String, updatedAt: Long)

    @Query("UPDATE chats SET selected_child_id = :messageId WHERE id = :chatId")
    suspend fun selectFirstMessage(chatId: String, messageId: String?)

    @Query("DELETE FROM chats WHERE id = :chatId")
    suspend fun delete(chatId: String)

    @Query("DELETE FROM chats")
    suspend fun deleteAll()
}
