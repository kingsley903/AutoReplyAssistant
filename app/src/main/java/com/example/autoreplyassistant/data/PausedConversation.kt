package com.example.autoreplyassistant.data

/**
 * Represents a conversation that has been manually paused via a chat command.
 *
 * The pause is persisted across process and device restarts using the
 * `paused_conversations` SQLite table defined in MessageQueueDatabase.
 */
data class PausedConversation(
    val conversationKey: String,
    val displayName: String,
    val pausedAt: Long
)
