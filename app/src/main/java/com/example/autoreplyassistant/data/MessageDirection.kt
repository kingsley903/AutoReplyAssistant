package com.example.autoreplyassistant.data

/**
 * Direction of a message within a conversation.
 *
 * INCOMING:  message from another person (the assistant may reply)
 * OUTGOING:  message from the account owner (the assistant must never reply)
 * UNCERTAIN: direction could not be reliably established from Android metadata;
 *            the assistant must not send a potentially incorrect reply.
 */
enum class MessageDirection {
    INCOMING,
    OUTGOING,
    UNCERTAIN
}