package com.example.autoreplyassistant.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase

/**
 * Persistent, conversation-specific message history.
 *
 * Stores every observed message (incoming and outgoing) per conversation,
 * isolated by conversation key. Used to provide the AI with the previous
 * three messages of context before generating a reply.
 *
 * Command messages are flagged with isCommand = true and excluded from
 * AI context.
 */
class ConversationHistoryRepository(private val dbHelper: MessageQueueDatabase) {

    /** Store a message in conversation history. */
    fun add(entry: ConversationHistoryEntry): Long {
        val db = dbHelper.writableDatabase
        return try {
            val values = ContentValues().apply {
                put("conversation_key", entry.conversationKey)
                put("package_name", entry.packageName)
                put("sender", entry.sender)
                put("message_text", entry.messageText)
                put("direction", entry.direction.name)
                put("timestamp_ms", entry.timestampMs)
                put("is_command", if (entry.isCommand) 1 else 0)
            }
            db.insert("conversation_history", null, values)
        } finally {
            db.close()
        }
    }

    /**
     * Retrieve the previous N non-command messages for a conversation,
     * in chronological order (oldest first).
     *
     * @param conversationKey The conversation identity
     * @param limit Maximum number of history entries to return (default 3)
     * @param beforeTimestampMs If provided, only return messages before this timestamp
     */
    fun getPreviousMessages(
        conversationKey: String,
        limit: Int = 3,
        beforeTimestampMs: Long = Long.MAX_VALUE
    ): List<ConversationHistoryEntry> {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT * FROM conversation_history " +
                    "WHERE conversation_key = ? " +
                    "AND is_command = 0 " +
                    "AND timestamp_ms < ? " +
                    "ORDER BY timestamp_ms ASC " +
                    "LIMIT ?",
                arrayOf(conversationKey, beforeTimestampMs.toString(), limit.toString())
            )
            readCursor(cursor)
        } finally {
            db.close()
        }
    }

    /** Get all history for a conversation (for debugging/auditing). */
    fun getAllForConversation(conversationKey: String): List<ConversationHistoryEntry> {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT * FROM conversation_history " +
                    "WHERE conversation_key = ? " +
                    "ORDER BY timestamp_ms ASC",
                arrayOf(conversationKey)
            )
            readCursor(cursor)
        } finally {
            db.close()
        }
    }

    /** Clear history for a conversation (e.g. when conversation is deleted). */
    fun clearForConversation(conversationKey: String): Int {
        val db = dbHelper.writableDatabase
        return try {
            db.delete(
                "conversation_history",
                "conversation_key = ?",
                arrayOf(conversationKey)
            )
        } finally {
            db.close()
        }
    }

    private fun readCursor(cursor: android.database.Cursor): List<ConversationHistoryEntry> {
        val entries = mutableListOf<ConversationHistoryEntry>()
        try {
            while (cursor.moveToNext()) {
                entries.add(
                    ConversationHistoryEntry(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        conversationKey = cursor.getString(cursor.getColumnIndexOrThrow("conversation_key")),
                        packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                        sender = cursor.getString(cursor.getColumnIndexOrThrow("sender")),
                        messageText = cursor.getString(cursor.getColumnIndexOrThrow("message_text")),
                        direction = MessageDirection.valueOf(
                            cursor.getString(cursor.getColumnIndexOrThrow("direction"))
                        ),
                        timestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("timestamp_ms")),
                        isCommand = cursor.getInt(cursor.getColumnIndexOrThrow("is_command")) == 1
                    )
                )
            }
        } finally {
            cursor.close()
        }
        return entries
    }
}