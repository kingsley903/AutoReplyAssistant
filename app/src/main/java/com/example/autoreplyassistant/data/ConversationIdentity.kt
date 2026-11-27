package com.example.autoreplyassistant.data

/**
 * Uniquely identifies a WhatsApp / WhatsApp Business conversation.
 *
 * The conversation key is deliberately not just the notification title, because
 * in group chats the title is the group name while individual members send messages.
 * We combine package name with the most stable conversation identifier available:
 * a group/chat ID if present in notification extras, otherwise the conversation
 * display name (title).
 */
data class ConversationIdentity(
    val packageName: String,           // com.whatsapp or com.whatsapp.w4b
    val conversationKey: String,       // stable key: "pkg|groupIdOrTitle"
    val displayName: String,           // human-readable conversation name
    val isGroup: Boolean               // true if this is a group conversation
) {
    fun toLogString(): String = "$packageName/$conversationKey (${if (isGroup) "group" else "individual"}: $displayName)"
}