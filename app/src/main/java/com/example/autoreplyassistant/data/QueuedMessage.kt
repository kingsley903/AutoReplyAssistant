package com.example.autoreplyassistant.data

/**
 * A persistent queue entry for an inbound WhatsApp message awaiting an AI reply.
 */
data class QueuedMessage(
    val id: Long = -1,
    val messageId: String,             // unique id: package|sender|hash|timestamp
    val packageName: String,
    val conversationKey: String,
    val conversationDisplayName: String,
    val actualSender: String,          // the person who sent the message
    val incomingMessageText: String,
    val originalTimestampMs: Long,     // when the message was originally sent
    val status: String = "PENDING",    // PENDING, PROCESSING, RETRY_WAIT, WAITING_FOR_NOTIFICATION, SENT, FAILED, SKIPPED, SUPERSEDED
    val retryCount: Int = 0,
    val nextRetryTimeMs: Long = 0,     // 0 = process immediately
    val lastError: String? = null,
    val creationTimestampMs: Long = System.currentTimeMillis(),
    val superseded: Boolean = false,
    val quotedMessageRef: String? = null  // reference to a quoted/replied-to message for /.old
)