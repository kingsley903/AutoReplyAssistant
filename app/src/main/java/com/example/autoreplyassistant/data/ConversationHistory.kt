package com.example.autoreplyassistant.data

/**
 * A single message entry in a conversation's persistent history.
 *
 * Direction is stored explicitly so the AI can understand who said what.
 * Command messages (/.this, /.all, /.old, etc.) are flagged and excluded
 * from AI context.
 */
data class ConversationHistoryEntry(
    val id: Long = -1,
    val conversationKey: String,
    val packageName: String,
    val sender: String,
    val messageText: String,
    val direction: MessageDirection,
    val timestampMs: Long,
    val isCommand: Boolean = false
)