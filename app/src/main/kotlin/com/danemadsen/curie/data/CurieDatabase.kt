package com.danemadsen.curie.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.danemadsen.curie.data.db.AttachmentDao
import com.danemadsen.curie.data.db.AttachmentEntity
import com.danemadsen.curie.data.db.ChatDao
import com.danemadsen.curie.data.db.ChatEntity
import com.danemadsen.curie.data.db.MessageDao
import com.danemadsen.curie.data.db.MessageEntity

/**
 * Chats, their message trees and the messages' attachments. The schema of every version is
 * exported to app/schemas: a new version needs a migration (never a destructive fallback).
 */
@Database(
    entities = [ChatEntity::class, MessageEntity::class, AttachmentEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class CurieDatabase : RoomDatabase() {
    internal abstract fun chatDao(): ChatDao

    internal abstract fun messageDao(): MessageDao

    internal abstract fun attachmentDao(): AttachmentDao

    companion object {
        private const val FILE_NAME = "curie.db"

        /** Opens the app's database; keep one instance per process. */
        fun create(context: Context): CurieDatabase {
            val builder = Room.databaseBuilder(context, CurieDatabase::class.java, FILE_NAME)
            return builder.build()
        }
    }
}
