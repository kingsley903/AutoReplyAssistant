package com.example.autoreplyassistant.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase

class MessageQueueRepository(val dbHelper: MessageQueueDatabase) {

    fun enqueue(message: QueuedMessage): Long {
        val db = dbHelper.writableDatabase
        return try {
            val values = ContentValues().apply {
                put("message_id", message.messageId)
                put("package_name", message.packageName)
                put("conversation_key", message.conversationKey)
                put("conversation_display_name", message.conversationDisplayName)
                put("actual_sender", message.actualSender)
                put("incoming_message_text", message.incomingMessageText)
                put("original_timestamp_ms", message.originalTimestampMs)
                put("status", message.status)
                put("retry_count", message.retryCount)
                put("next_retry_time_ms", message.nextRetryTimeMs)
                put("last_error", message.lastError.orEmpty())
                put("creation_timestamp_ms", message.creationTimestampMs)
                put("superseded", if (message.superseded) 1 else 0)
                put("quoted_message_ref", message.quotedMessageRef.orEmpty())
            }
            db.insertWithOnConflict("message_queue", null, values, SQLiteDatabase.CONFLICT_IGNORE)
        } finally {
            db.close()
        }
    }

    fun getDueMessages(nowMs: Long): List<QueuedMessage> {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT * FROM message_queue " +
                    "WHERE status IN ('PENDING', 'RETRY_WAIT') " +
                    "AND next_retry_time_ms <= ? " +
                    "ORDER BY conversation_key, creation_timestamp_ms ASC",
                arrayOf(nowMs.toString())
            )
            readCursor(cursor)
        } finally {
            db.close()
        }
    }

    fun getById(id: Long): QueuedMessage? {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT * FROM message_queue WHERE id = ?",
                arrayOf(id.toString())
            )
            val list = readCursor(cursor)
            list.firstOrNull()
        } finally {
            db.close()
        }
    }

    fun getByMessageId(messageId: String): QueuedMessage? {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT * FROM message_queue WHERE message_id = ?",
                arrayOf(messageId)
            )
            val list = readCursor(cursor)
            list.firstOrNull()
        } finally {
            db.close()
        }
    }

    fun exists(messageId: String): Boolean {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT 1 FROM message_queue WHERE message_id = ? LIMIT 1",
                arrayOf(messageId)
            )
            val exists = cursor.moveToFirst()
            cursor.close()
            exists
        } finally {
            db.close()
        }
    }

    fun updateStatus(
        id: Long,
        status: String,
        retryCount: Int = -1,
        nextRetryTimeMs: Long = -1,
        lastError: String? = null
    ): Int {
        val db = dbHelper.writableDatabase
        return try {
            val values = ContentValues().apply {
                put("status", status)
                if (retryCount >= 0) put("retry_count", retryCount)
                if (nextRetryTimeMs >= 0) put("next_retry_time_ms", nextRetryTimeMs)
                if (lastError != null) put("last_error", lastError)
            }
            db.update("message_queue", values, "id = ?", arrayOf(id.toString()))
        } finally {
            db.close()
        }
    }

    fun markSent(id: Long): Int = updateStatus(id, "SENT")

    fun markFailed(id: Long, lastError: String): Int = updateStatus(id, "FAILED", nextRetryTimeMs = -1, lastError = lastError)

    fun markSkipped(id: Long, reason: String): Int = updateStatus(id, "SKIPPED", lastError = reason)

    fun delete(id: Long): Int {
        val db = dbHelper.writableDatabase
        return try {
            db.delete("message_queue", "id = ?", arrayOf(id.toString()))
        } finally {
            db.close()
        }
    }

    /** Mark a message as superseded by owner reply. */
    fun markSuperseded(id: Long, reason: String): Int {
        val db = dbHelper.writableDatabase
        return try {
            val values = ContentValues().apply {
                put("status", "SUPERSEDED")
                put("last_error", reason)
            }
            db.update("message_queue", values, "id = ?", arrayOf(id.toString()))
        } finally {
            db.close()
        }
    }

    /** Get all pending/superseded messages for a conversation. */
    fun getMessagesForConversation(conversationKey: String, status: String): List<QueuedMessage> {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT * FROM message_queue WHERE conversation_key = ? AND status = ? ORDER BY creation_timestamp_ms ASC",
                arrayOf(conversationKey, status)
            )
            readCursor(cursor)
        } finally {
            db.close()
        }
    }

    fun countByStatus(status: String): Int {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM message_queue WHERE status = ?",
                arrayOf(status)
            )
            val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()
            count
        } finally {
            db.close()
        }
    }

    private fun readCursor(cursor: Cursor): List<QueuedMessage> {
        val messages = mutableListOf<QueuedMessage>()
        try {
            while (cursor.moveToNext()) {
                messages.add(
                    QueuedMessage(
                        id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                        messageId = cursor.getString(cursor.getColumnIndexOrThrow("message_id")),
                        packageName = cursor.getString(cursor.getColumnIndexOrThrow("package_name")),
                        conversationKey = cursor.getString(cursor.getColumnIndexOrThrow("conversation_key")),
                        conversationDisplayName = cursor.getString(cursor.getColumnIndexOrThrow("conversation_display_name")),
                        actualSender = cursor.getString(cursor.getColumnIndexOrThrow("actual_sender")),
                        incomingMessageText = cursor.getString(cursor.getColumnIndexOrThrow("incoming_message_text")),
                        originalTimestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("original_timestamp_ms")),
                        status = cursor.getString(cursor.getColumnIndexOrThrow("status")),
                        retryCount = cursor.getInt(cursor.getColumnIndexOrThrow("retry_count")),
                        nextRetryTimeMs = cursor.getLong(cursor.getColumnIndexOrThrow("next_retry_time_ms")),
                        lastError = cursor.getString(cursor.getColumnIndexOrThrow("last_error")).takeIf { it.isNotEmpty() },
                        creationTimestampMs = cursor.getLong(cursor.getColumnIndexOrThrow("creation_timestamp_ms")),
                        superseded = cursor.getInt(cursor.getColumnIndexOrThrow("superseded")) == 1,
                        quotedMessageRef = cursor.getString(cursor.getColumnIndexOrThrow("quoted_message_ref")).takeIf { it.isNotEmpty() }
                    )
                )
            }
        } finally {
            cursor.close()
        }
        return messages
    }
}