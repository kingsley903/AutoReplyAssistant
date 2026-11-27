package com.example.autoreplyassistant.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import java.util.concurrent.TimeUnit

/**
 * Persistence layer for chat control states:
 * - Global auto-reply pause (settings DataStore)
 * - Individually paused conversations (SQLite paused_conversations table)
 *
 * Both survive process termination and device restarts.
 */
class ChatControlRepository(private val dbHelper: MessageQueueDatabase) {

    /**
     * Returns true if the conversation is individually paused.
     */
    fun isPaused(conversationKey: String): Boolean {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT 1 FROM paused_conversations WHERE conversation_key = ? LIMIT 1",
                arrayOf(conversationKey)
            )
            val exists = cursor.moveToFirst()
            cursor.close()
            exists
        } finally {
            db.close()
        }
    }

    fun pause(conversationKey: String, displayName: String) {
        val db = dbHelper.writableDatabase
        try {
            val values = ContentValues().apply {
                put("conversation_key", conversationKey)
                put("display_name", displayName)
                put("paused_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(
                "paused_conversations",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } finally {
            db.close()
        }
    }

    fun resume(conversationKey: String): Int {
        val db = dbHelper.writableDatabase
        return try {
            db.delete(
                "paused_conversations",
                "conversation_key = ?",
                arrayOf(conversationKey)
            )
        } finally {
            db.close()
        }
    }

    fun resumeAll(): Int {
        val db = dbHelper.writableDatabase
        return try {
            val count = db.delete("paused_conversations", null, null)
            count
        } finally {
            db.close()
        }
    }

    fun getAllPaused(): List<PausedConversation> {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT conversation_key, display_name, paused_at FROM paused_conversations ORDER BY paused_at ASC",
                null
            )
            val list = mutableListOf<PausedConversation>()
            try {
                while (cursor.moveToNext()) {
                    list.add(
                        PausedConversation(
                            conversationKey = cursor.getString(cursor.getColumnIndexOrThrow("conversation_key")),
                            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                            pausedAt = cursor.getLong(cursor.getColumnIndexOrThrow("paused_at"))
                        )
                    )
                }
            } finally {
                cursor.close()
            }
            list
        } finally {
            db.close()
        }
    }

    /** Resolve paused time as human-readable string. */
    fun formatPausedDuration(pausedAt: Long): String {
        val elapsed = System.currentTimeMillis() - pausedAt
        val minutes = TimeUnit.MILLISECONDS.toMinutes(elapsed)
        return if (minutes < 1) "just now"
        else if (minutes < 60) "${minutes} min${if (minutes != 1) "s" else ""} ago"
        else {
            val hours = TimeUnit.MILLISECONDS.toHours(elapsed)
            "${hours} hour${if (hours != 1) "s" else ""} ago"
        }
    }

    // === Global pause exceptions ===

    fun isException(conversationKey: String): Boolean {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT 1 FROM global_pause_exceptions WHERE conversation_key = ? LIMIT 1",
                arrayOf(conversationKey)
            )
            val exists = cursor.moveToFirst()
            cursor.close()
            exists
        } finally {
            db.close()
        }
    }

    fun addException(conversationKey: String, displayName: String) {
        val db = dbHelper.writableDatabase
        try {
            val values = ContentValues().apply {
                put("conversation_key", conversationKey)
                put("display_name", displayName)
                put("added_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict(
                "global_pause_exceptions",
                null,
                values,
                SQLiteDatabase.CONFLICT_REPLACE
            )
        } finally {
            db.close()
        }
    }

    fun removeException(conversationKey: String): Int {
        val db = dbHelper.writableDatabase
        return try {
            db.delete(
                "global_pause_exceptions",
                "conversation_key = ?",
                arrayOf(conversationKey)
            )
        } finally {
            db.close()
        }
    }

    fun clearAllExceptions(): Int {
        val db = dbHelper.writableDatabase
        return try {
            val count = db.delete("global_pause_exceptions", null, null)
            count
        } finally {
            db.close()
        }
    }

    fun getAllExceptions(): List<PausedConversation> {
        val db = dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT conversation_key, display_name, added_at FROM global_pause_exceptions ORDER BY added_at ASC",
                null
            )
            val list = mutableListOf<PausedConversation>()
            try {
                while (cursor.moveToNext()) {
                    list.add(
                        PausedConversation(
                            conversationKey = cursor.getString(cursor.getColumnIndexOrThrow("conversation_key")),
                            displayName = cursor.getString(cursor.getColumnIndexOrThrow("display_name")),
                            pausedAt = cursor.getLong(cursor.getColumnIndexOrThrow("added_at"))
                        )
                    )
                }
            } finally {
                cursor.close()
            }
            list
        } finally {
            db.close()
        }
    }
}
