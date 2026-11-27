package com.example.autoreplyassistant.service

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.autoreplyassistant.data.ConversationIdentity
import com.example.autoreplyassistant.data.MessageDirection

object NotificationParser {

    private const val TAG = "NotificationParser"

    private const val WHATSAPP = "com.whatsapp"
    private const val WHATSAPP_BUSINESS = "com.whatsapp.w4b"
    private const val FACEBOOK_MESSENGER = "com.facebook.orca"
    private const val FACEBOOK_MESSENGER_LITE = "com.facebook.lite"

    val supportedPackages: Set<String> = setOf(WHATSAPP, WHATSAPP_BUSINESS, FACEBOOK_MESSENGER, FACEBOOK_MESSENGER_LITE)

    fun parse(
        sbn: StatusBarNotification,
        ownerName: String
    ): ParsedNotification? {
        val packageName = sbn.packageName
        if (packageName !in supportedPackages) return null

        val notification = sbn.notification ?: run {
            Log.w(TAG, "parse: notification is null for ${sbn.packageName}")
            return null
        }

        val flags = notification.flags
        if ((flags and Notification.FLAG_GROUP_SUMMARY) != 0) return null
        if ((flags and Notification.FLAG_ONGOING_EVENT) != 0) return null

        val extras = notification.extras ?: run {
            Log.w(TAG, "parse: extras is null for ${sbn.packageName}")
            return null
        }
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()?.trim()
        val postTimeMs = sbn.postTime

        if (title.isBlank() && body.isBlank()) {
            Log.w(TAG, "parse: title and body both blank for ${sbn.packageName}")
            return null
        }

        val quotedText = extractQuotedText(extras)
        val quotedSender = extractQuotedSender(extras)

        val rawMessages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        val messages = if (rawMessages != null) {
            Notification.MessagingStyle.Message.getMessagesFromBundleArray(rawMessages)
        } else null

        if (messages.isNullOrEmpty()) {
            Log.w(TAG, "parse: no MessagingStyle messages, using fallback for ${sbn.packageName}")
            return parseFallback(
                packageName = packageName,
                title = title,
                body = body,
                postTimeMs = postTimeMs,
                quotedText = quotedText,
                quotedSender = quotedSender
            )
        }

        val conversationTitleExtra = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val conversationTitle = conversationTitleExtra
            ?: title.takeIf { it.isNotEmpty() }
            ?: "WhatsApp conversation"

        val distinctSenders = messages
            .map { extractSenderName(it) }
            .filter { it.isNotBlank() }
            .toSet()
        val isGroupExtra = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        } else false
        val isGroup = isGroupExtra || distinctSenders.size > 1 || conversationTitleExtra != null

        val conversationKey = buildConversationKey(
            packageName = packageName,
            conversationTitle = conversationTitle,
            infoText = infoText,
            isGroup = isGroup
        )

        val identity = ConversationIdentity(
            packageName = packageName,
            conversationKey = conversationKey,
            displayName = conversationTitle,
            isGroup = isGroup
        )

        val latestMessage = messages.lastOrNull() ?: return null
        val latestSender = extractSenderName(latestMessage)
        val latestText = latestMessage.text?.toString()?.trim().orEmpty()
        val latestTimestamp = if (latestMessage.timestamp > 0L) latestMessage.timestamp else postTimeMs

        if (latestText.isBlank()) {
            Log.w(TAG, "parse: latest text blank for ${sbn.packageName}")
            return null
        }

        val direction = determineDirection(
            latestSender = latestSender,
            ownerName = ownerName,
            isGroup = isGroup,
            distinctSenders = distinctSenders
        )

        val messageId = buildMessageId(
            packageName = packageName,
            sender = latestSender,
            text = latestText,
            timestampMs = latestTimestamp
        )

        return ParsedNotification(
            identity = identity,
            direction = direction,
            actualSender = latestSender.takeIf { it.isNotBlank() } ?: "Unknown",
            messageText = latestText,
            messageId = messageId,
            originalTimestampMs = latestTimestamp,
            notificationPostTimeMs = postTimeMs,
            quotedMessageText = quotedText,
            quotedMessageSender = quotedSender
        )
    }

    private fun parseFallback(
        packageName: String,
        title: String,
        body: String,
        postTimeMs: Long,
        quotedText: String? = null,
        quotedSender: String? = null
    ): ParsedNotification {
        val conversationKey = buildConversationKey(
            packageName = packageName,
            conversationTitle = title,
            infoText = null,
            isGroup = false
        )
        val messageId = buildMessageId(
            packageName = packageName,
            sender = title,
            text = body,
            timestampMs = postTimeMs
        )
        return ParsedNotification(
            identity = ConversationIdentity(
                packageName = packageName,
                conversationKey = conversationKey,
                displayName = title.takeIf { it.isNotEmpty() } ?: "WhatsApp conversation",
                isGroup = false
            ),
            direction = MessageDirection.UNCERTAIN,
            actualSender = title.takeIf { it.isNotEmpty() } ?: "Unknown",
            messageText = body,
            messageId = messageId,
            originalTimestampMs = postTimeMs,
            notificationPostTimeMs = postTimeMs,
            quotedMessageText = quotedText,
            quotedMessageSender = quotedSender
        )
    }

    /**
     * Attempt to extract quoted message text from notification extras.
     * WhatsApp may include quoted content in various extra fields when
     * a user replies to a specific message.
     */
    private fun extractQuotedText(extras: Bundle): String? {
        val keys = arrayOf(
            "android.text",
            "android.subText",
            "com.whatsapp.quoted_text",
            "quoted_text",
            "reply_text"
        )
        for (key in keys) {
            val value = extras.getCharSequence(key)?.toString()?.trim()
            if (!value.isNullOrBlank()) return value
        }
        val textLines = extras.getCharSequenceArray("android.textLines")
        if (textLines != null && textLines.size >= 2) {
            val first = textLines.first().toString().trim()
            val last = textLines.last().toString().trim()
            if (first != last && first.isNotBlank()) return first
        }
        return null
    }

    /**
     * Attempt to extract quoted message sender from notification extras.
     */
    private fun extractQuotedSender(extras: Bundle): String? {
        val keys = arrayOf(
            "android.title",
            "com.whatsapp.quoted_sender",
            "quoted_sender"
        )
        for (key in keys) {
            val value = extras.getCharSequence(key)?.toString()?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    private fun extractSenderName(message: Notification.MessagingStyle.Message): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val personName = message.senderPerson?.name?.toString()?.trim()
            if (!personName.isNullOrBlank()) return personName
        }
        return message.sender?.toString()?.trim().orEmpty()
    }

    private fun buildConversationKey(
        packageName: String,
        conversationTitle: String?,
        infoText: String?,
        isGroup: Boolean
    ): String {
        val stableId = if (isGroup && !infoText.isNullOrBlank()) infoText else conversationTitle
        return "$packageName|${stableId?.takeIf { it.isNotEmpty() } ?: "unknown"}"
    }

    private fun buildMessageId(
        packageName: String,
        sender: String,
        text: String,
        timestampMs: Long
    ): String {
        val textHash = text.hashCode().toString()
        return "$packageName|$sender|$textHash|$timestampMs"
    }

    internal fun determineDirection(
        latestSender: String,
        ownerName: String,
        isGroup: Boolean,
        distinctSenders: Set<String>
    ): MessageDirection {
        if (ownerName.isBlank()) {
            return MessageDirection.UNCERTAIN
        }

        val normalizedOwner = ownerName.trim().lowercase()
        val normalizedSender = latestSender.trim().lowercase()

        if (normalizedSender == normalizedOwner) {
            return MessageDirection.OUTGOING
        }

        return MessageDirection.INCOMING
    }
}

/**
 * Result of parsing a WhatsApp notification.
 */
data class ParsedNotification(
    val identity: ConversationIdentity,
    val direction: MessageDirection,
    val actualSender: String,
    val messageText: String,
    val messageId: String,
    val originalTimestampMs: Long,
    val notificationPostTimeMs: Long,
    val quotedMessageText: String? = null,
    val quotedMessageSender: String? = null
)