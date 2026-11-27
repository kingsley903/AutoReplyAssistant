package com.example.autoreplyassistant.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.LruCache
import com.example.autoreplyassistant.data.ActivityLogStore
import com.example.autoreplyassistant.data.ChatControlRepository
import com.example.autoreplyassistant.data.ConversationHistoryEntry
import com.example.autoreplyassistant.data.ConversationHistoryRepository
import com.example.autoreplyassistant.data.MessageDirection
import com.example.autoreplyassistant.data.MessageQueueDatabase
import com.example.autoreplyassistant.data.MessageQueueRepository
import com.example.autoreplyassistant.data.QueuedMessage
import com.example.autoreplyassistant.data.SettingsRepository
import com.example.autoreplyassistant.data.SupersededMessageTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WhatsAppReplyListenerService : NotificationListenerService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var queueProcessor: QueueProcessor
    private lateinit var chatControl: ChatControlRepository
    private lateinit var historyRepository: ConversationHistoryRepository
    private lateinit var supersededTracker: SupersededMessageTracker
    private lateinit var messageRepository: MessageQueueRepository

    private val history = object : LruCache<String, MutableList<String>>(100) {}
    private val recentlyHandled = ConcurrentHashMap.newKeySet<String>()
    private val senderLastReplyAt = ConcurrentHashMap<String, Long>()

    // WhatsApp command prefixes — matched case-insensitively, entire message must match
    private val COMMANDS = setOf(
        "/.this", "/.all", "/.resume", "/.resumeall",
        "/.old", "/.r.this", "/.r.all"
    )

    override fun onCreate() {
        super.onCreate()
        val dbHelper = MessageQueueDatabase(applicationContext)
        queueProcessor = QueueProcessor(applicationContext, dbHelper)
        queueProcessor.start()
        chatControl = ChatControlRepository(dbHelper)
        historyRepository = ConversationHistoryRepository(dbHelper)
        messageRepository = MessageQueueRepository(dbHelper)
        supersededTracker = SupersededMessageTracker(messageRepository)
        ActivityLogStore.add("SERVICE", "WhatsApp reply listener service started")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in NotificationParser.supportedPackages) return

        serviceScope.launch {
            try {
                val ownerName = getOwnerName()
                val parsed = NotificationParser.parse(sbn, ownerName) ?: return@launch

                ActivityLogStore.add(
                    "CONVERSATION",
                    "${parsed.identity.toLogString()} | sender: ${parsed.actualSender} | direction: ${parsed.direction}"
                )

                // Store every observed message in persistent conversation history
                val isCmd = parsed.direction == MessageDirection.OUTGOING && ownerName.isNotBlank() &&
                    COMMANDS.contains(parsed.messageText.trim().lowercase())
                historyRepository.add(
                    ConversationHistoryEntry(
                        conversationKey = parsed.identity.conversationKey,
                        packageName = parsed.identity.packageName,
                        sender = parsed.actualSender,
                        messageText = parsed.messageText,
                        direction = parsed.direction,
                        timestampMs = parsed.originalTimestampMs,
                        isCommand = isCmd
                    )
                )

                // === COMMAND DETECTION ===
                // Commands only execute when message is OUTGOING and authored by the account owner.
                // If direction is UNCERTAIN, never execute a command.
                if (parsed.direction == MessageDirection.OUTGOING && ownerName.isNotBlank()) {
                    val trimmedText = parsed.messageText.trim()
                    val lowerText = trimmedText.lowercase()
                    if (COMMANDS.contains(lowerText)) {
                        handleCommand(parsed, lowerText, ownerName)
                        return@launch
                    }
                }

                // For OUTGOING messages from owner, check if they supersede pending incoming messages
                if (parsed.direction == MessageDirection.OUTGOING && ownerName.isNotBlank()) {
                    supersededTracker.markConversationSuperseded(parsed.identity.conversationKey)
                    ActivityLogStore.add(
                        "SKIPPED",
                        "Outgoing message from ${parsed.actualSender} in ${parsed.identity.displayName} — not replying, checked for supersede"
                    )
                    return@launch
                }

                when (parsed.direction) {
                    MessageDirection.OUTGOING -> {
                        ActivityLogStore.add(
                            "SKIPPED",
                            "Outgoing message in ${parsed.identity.displayName} — not replying"
                        )
                        return@launch
                    }
                    MessageDirection.UNCERTAIN -> {
                        ActivityLogStore.add(
                            "SKIPPED",
                            "Direction uncertain for message in ${parsed.identity.displayName} — not replying"
                        )
                        return@launch
                    }
                    MessageDirection.INCOMING -> {
                        // Proceed to queue
                    }
                }

                if (NotificationParser.supportedPackages.contains(sbn.packageName)) {
                    if (recentlyHandled.contains(parsed.messageId)) {
                        ActivityLogStore.add(
                            "SKIPPED",
                            "Duplicate message suppressed: ${parsed.actualSender} in ${parsed.identity.displayName}"
                        )
                        return@launch
                    }
                    recentlyHandled.add(parsed.messageId)
                    trimHandledKeys()
                }

                val now = System.currentTimeMillis()
                val last = senderLastReplyAt[parsed.actualSender.lowercase()] ?: 0L
                if (now - last < MIN_SENDER_COOLDOWN_MS) {
                    ActivityLogStore.add(
                        "SKIPPED",
                        "Cooldown active for ${parsed.actualSender} in ${parsed.identity.displayName}"
                    )
                    return@launch
                }

                val queued = QueuedMessage(
                    messageId = parsed.messageId,
                    packageName = parsed.identity.packageName,
                    conversationKey = parsed.identity.conversationKey,
                    conversationDisplayName = parsed.identity.displayName,
                    actualSender = parsed.actualSender,
                    incomingMessageText = parsed.messageText,
                    originalTimestampMs = parsed.originalTimestampMs,
                    status = "PENDING",
                    quotedMessageRef = parsed.quotedMessageText
                )

                val insertedId = queueProcessor.enqueue(queued)
                if (insertedId >= 0) {
                    ActivityLogStore.add(
                        "QUEUED",
                        "Message from ${parsed.actualSender} queued for ${parsed.identity.displayName}"
                    )
                } else {
                    ActivityLogStore.add(
                        "SKIPPED",
                        "Duplicate message already in queue: ${parsed.actualSender} in ${parsed.identity.displayName}"
                    )
                    return@launch
                }

                senderLastReplyAt[parsed.actualSender.lowercase()] = now

                queueProcessor.wakeUp()

            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ActivityLogStore.add(
                    "ERROR",
                    "Notification processing failed for ${sbn.packageName}: ${error.message ?: error.javaClass.simpleName}"
                )
            }
        }
    }

    /**
     * Handles chat control commands sent by the account owner.
     * Command messages are never queued, never sent to OpenRouter, and never added to AI history.
     */
    private suspend fun handleCommand(parsed: ParsedNotification, command: String, ownerName: String) {
        val conversationKey = parsed.identity.conversationKey
        val displayName = parsed.identity.displayName
        val packageName = parsed.identity.packageName

        when (command) {
            "/.this" -> {
                // Pause current conversation only
                chatControl.pause(conversationKey, displayName)
                ActivityLogStore.add(
                    "COMMAND",
                    "Conversation paused via /.this: $displayName ($packageName)"
                )
            }
            "/.all" -> {
                // Pause all conversations globally
                SettingsRepository(applicationContext).updateGlobalPause(true)
                ActivityLogStore.add(
                    "COMMAND",
                    "Global auto-reply paused via /.all (all conversations)"
                )
            }
            "/.resume" -> {
                // Resume current conversation only (does not override global pause)
                chatControl.resume(conversationKey)
                ActivityLogStore.add(
                    "COMMAND",
                    "Conversation resumed via /.resume: $displayName ($packageName)"
                )
            }
            "/.resumeall" -> {
                // Resume global auto-replies (does not unpause individual conversations)
                SettingsRepository(applicationContext).updateGlobalPause(false)
                ActivityLogStore.add(
                    "COMMAND",
                    "Global auto-reply resumed via /.resumeall"
                )
            }
            "/.old" -> {
                // Manual old-message response: respond to quoted message
                handleOldCommand(parsed, ownerName)
            }
            "/.r.this" -> {
                // Reactivate current conversation (exception to global pause)
                chatControl.resume(conversationKey)
                chatControl.addException(conversationKey, displayName)
                SettingsRepository(applicationContext).updateGlobalPause(false)
                // Note: global pause is turned off, but this conversation is explicitly allowed
                // Other conversations remain paused via paused_conversations table
                ActivityLogStore.add(
                    "COMMAND",
                    "Conversation reactivated via /.R.this: $displayName ($packageName) — added as global pause exception"
                )
            }
            "/.r.all" -> {
                // Reactivate all conversations
                SettingsRepository(applicationContext).updateGlobalPause(false)
                chatControl.clearAllExceptions()
                chatControl.resumeAll()
                ActivityLogStore.add(
                    "COMMAND",
                    "All conversations reactivated via /.R.all"
                )
            }
        }
    }

    /**
     * Handle /.old command — respond to a quoted/old message.
     * Extracts quoted message from notification metadata and queues it for AI response.
     */
    private fun handleOldCommand(parsed: ParsedNotification, ownerName: String) {
        val quotedText = parsed.quotedMessageText
        val quotedSender = parsed.quotedMessageSender

        if (quotedText.isNullOrBlank()) {
            ActivityLogStore.add(
                "ERROR",
                "/.old command: WhatsApp did not expose quoted message metadata. Cannot determine which message to respond to."
            )
            return
        }

        val targetSender = quotedSender?.takeIf { it.isNotBlank() } ?: parsed.actualSender

        // Never respond to owner-authored messages
        if (ownerName.isNotBlank() && targetSender.trim().lowercase() == ownerName.trim().lowercase()) {
            ActivityLogStore.add(
                "SKIPPED",
                "/.old command: quoted message is from account owner ($targetSender) — not replying"
            )
            return
        }

        val queued = QueuedMessage(
            messageId = "old_${parsed.messageId}_${System.currentTimeMillis()}",
            packageName = parsed.identity.packageName,
            conversationKey = parsed.identity.conversationKey,
            conversationDisplayName = parsed.identity.displayName,
            actualSender = targetSender,
            incomingMessageText = quotedText,
            originalTimestampMs = parsed.originalTimestampMs,
            status = "PENDING",
            quotedMessageRef = quotedText
        )

        val insertedId = queueProcessor.enqueueOldMessage(queued)
        if (insertedId >= 0) {
            ActivityLogStore.add(
                "QUEUED",
                "/.old: queued response to quoted message from $targetSender in ${parsed.identity.displayName}"
            )
        } else {
            ActivityLogStore.add(
                "SKIPPED",
                "/.old: duplicate manual request suppressed"
            )
        }
    }

    private suspend fun getOwnerName(): String {
        return try {
            val repo = SettingsRepository(applicationContext)
            repo.settings.first().ownerName
        } catch (e: Exception) {
            ""
        }
    }

    private fun trimHandledKeys() {
        if (recentlyHandled.size > 500) {
            recentlyHandled.clear()
        }
    }

    override fun onDestroy() {
        queueProcessor.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val MIN_SENDER_COOLDOWN_MS = 5_000L
    }
}
