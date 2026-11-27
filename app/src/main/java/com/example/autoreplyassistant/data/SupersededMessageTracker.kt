package com.example.autoreplyassistant.data

/**
 * Tracks which queued messages have been superseded by an owner reply.
 *
 * When the account owner manually replies to an incoming message,
 * any pending AI reply for that message is cancelled. The message
 * is marked SUPERSEDED and will never be generated or delivered.
 */
class SupersededMessageTracker(private val repository: MessageQueueRepository) {

    /**
     * Mark a queued message as superseded by a human reply.
     * Returns true if the message was found and marked.
     */
    fun markSuperseded(messageId: String): Boolean {
        val msg = repository.getByMessageId(messageId)
        if (msg == null || msg.status == "SUPERSEDED") return false

        repository.markSuperseded(msg.id, "Superseded by owner reply")
        ActivityLogStore.add(
            "HUMAN_REPLIED",
            "Message from ${msg.actualSender} in ${msg.conversationDisplayName} superseded by owner reply"
        )
        return true
    }

    /**
     * Mark all pending messages for a conversation as superseded.
     * Used when the owner sends a general reply in a conversation.
     */
    fun markConversationSuperseded(conversationKey: String): Int {
        val now = System.currentTimeMillis()
        val pending = repository.getMessagesForConversation(conversationKey, "PENDING")
        var count = 0
        for (msg in pending) {
            repository.markSuperseded(msg.id, "Superseded by owner reply in conversation")
            count++
        }
        return count
    }

    /** Check whether a message has been superseded. */
    fun isSuperseded(messageId: String): Boolean {
        val msg = repository.getByMessageId(messageId)
        return msg != null && msg.status == "SUPERSEDED"
    }

    /** Get count of superseded messages (for dashboard). */
    fun countSuperseded(): Int {
        val db = repository.dbHelper.readableDatabase
        return try {
            val cursor = db.rawQuery(
                "SELECT COUNT(*) FROM message_queue WHERE status = 'SUPERSEDED'",
                null
            )
            val count = if (cursor.moveToFirst()) cursor.getInt(0) else 0
            cursor.close()
            count
        } finally {
            db.close()
        }
    }
}