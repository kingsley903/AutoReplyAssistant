package com.example.autoreplyassistant.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * Persistent SQLite storage for the message retry queue.
 *
 * No Room dependency — uses the platform's built-in SQLiteOpenHelper directly,
 * keeping minSdk 26 compatible and dependency surface minimal.
 */
class MessageQueueDatabase(context: Context) : SQLiteOpenHelper(
    context,
    "message_queue.db",
    null,
    2
) {

    override fun onCreate(db: SQLiteDatabase) {
        // Existing message queue table
        db.execSQL("""
            CREATE TABLE message_queue (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                message_id TEXT UNIQUE NOT NULL,
                package_name TEXT NOT NULL,
                conversation_key TEXT NOT NULL,
                conversation_display_name TEXT,
                actual_sender TEXT,
                incoming_message_text TEXT,
                original_timestamp_ms INTEGER,
                status TEXT NOT NULL DEFAULT 'PENDING',
                retry_count INTEGER NOT NULL DEFAULT 0,
                next_retry_time_ms INTEGER NOT NULL DEFAULT 0,
                last_error TEXT,
                creation_timestamp_ms INTEGER NOT NULL,
                superseded INTEGER NOT NULL DEFAULT 0,
                quoted_message_ref TEXT
            )
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX idx_queue_status_next_retry
            ON message_queue(status, next_retry_time_ms)
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX idx_queue_conversation
            ON message_queue(conversation_key)
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX idx_queue_message_id
            ON message_queue(message_id)
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX idx_queue_superseded
            ON message_queue(superseded)
        """.trimIndent())

        // Table for paused conversations (individual pauses)
        db.execSQL("""
            CREATE TABLE paused_conversations (
                conversation_key TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                paused_at LONG NOT NULL
            )
        """.trimIndent())

        // Table for conversation message history (persistent, per-conversation)
        db.execSQL("""
            CREATE TABLE conversation_history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                conversation_key TEXT NOT NULL,
                package_name TEXT NOT NULL,
                sender TEXT NOT NULL,
                message_text TEXT NOT NULL,
                direction TEXT NOT NULL,
                timestamp_ms INTEGER NOT NULL,
                is_command INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())

        db.execSQL("""
            CREATE INDEX idx_history_conversation
            ON conversation_history(conversation_key, timestamp_ms)
        """.trimIndent())

        // Table for conversations explicitly allowed during global pause
        db.execSQL("""
            CREATE TABLE global_pause_exceptions (
                conversation_key TEXT PRIMARY KEY,
                display_name TEXT NOT NULL,
                added_at LONG NOT NULL
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // Add superseded and quoted_message_ref columns to existing message_queue
            db.execSQL("ALTER TABLE message_queue ADD COLUMN superseded INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE message_queue ADD COLUMN quoted_message_ref TEXT")
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS idx_queue_superseded
                ON message_queue(superseded)
            """.trimIndent())

            // Create conversation_history table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS conversation_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    conversation_key TEXT NOT NULL,
                    package_name TEXT NOT NULL,
                    sender TEXT NOT NULL,
                    message_text TEXT NOT NULL,
                    direction TEXT NOT NULL,
                    timestamp_ms INTEGER NOT NULL,
                    is_command INTEGER NOT NULL DEFAULT 0
                )
            """.trimIndent())
            db.execSQL("""
                CREATE INDEX IF NOT EXISTS idx_history_conversation
                ON conversation_history(conversation_key, timestamp_ms)
            """.trimIndent())

            // Create global_pause_exceptions table
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS global_pause_exceptions (
                    conversation_key TEXT PRIMARY KEY,
                    display_name TEXT NOT NULL,
                    added_at LONG NOT NULL
                )
            """.trimIndent())
        }
    }
}