package com.example.autoreplyassistant.service

import android.app.Notification
import android.app.Notification.Action
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.example.autoreplyassistant.data.ActivityLogStore
import com.example.autoreplyassistant.data.ChatControlRepository
import com.example.autoreplyassistant.data.ConversationHistoryEntry
import com.example.autoreplyassistant.data.ConversationHistoryRepository
import com.example.autoreplyassistant.data.MessageDirection
import com.example.autoreplyassistant.data.MessageQueueDatabase
import com.example.autoreplyassistant.data.MessageQueueRepository
import com.example.autoreplyassistant.data.QueuedMessage
import com.example.autoreplyassistant.network.ReplyGenerator
import com.example.autoreplyassistant.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class QueueProcessor(
    private val context: Context,
    private val dbHelper: MessageQueueDatabase,
    private val replyGenerator: ReplyGenerator = ReplyGenerator()
) {

    private val repository = MessageQueueRepository(dbHelper)
    private val settingsRepository = SettingsRepository(context.applicationContext)
    private val chatControl = ChatControlRepository(dbHelper)
    private val historyRepository = ConversationHistoryRepository(dbHelper)
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val conversationLocks = ConcurrentHashMap<String, Any>()
    private val processingConversations = ConcurrentHashMap.newKeySet<String>()
    private var isRunning = false

    fun start() {
        if (isRunning) return
        isRunning = true
        serviceScope.launch {
            processQueueLoop()
        }
    }

    fun shutdown() {
        isRunning = false
        serviceScope.cancel()
    }

    private fun processQueueLoop() {
        while (isRunning) {
            try {
                processDueMessages()
            } catch (e: Exception) {
                ActivityLogStore.add("ERROR", "Queue processor loop: ${e.message ?: e.javaClass.simpleName}")
            }
            delay(5000)
        }
    }

    private fun processDueMessages() {
        val now = System.currentTimeMillis()
        val due = repository.getDueMessages(now)
        if (due.isEmpty()) return

        val byConversation = due.groupBy { it.conversationKey }

        for ((conversationKey, messages) in byConversation) {
            val lock = conversationLocks.putIfAbsent(conversationKey, Any()) ?: conversationLocks[conversationKey]!!
            synchronized(lock) {
                if (!processingConversations.add(conversationKey)) {
                    continue
                }
                try {
                    for (message in messages) {
                        if (!isRunning) break
                        processSingleMessage(message)
                    }
                } finally {
                    processingConversations.remove(conversationKey)
                }
            }
        }
    }

    private fun processSingleMessage(message: QueuedMessage) {
        val id = message.id

        if (message.status != "PENDING" && message.status != "RETRY_WAIT") return

        val settings = settingsRepository.settings.first()

        if (!settings.autoReplyEnabled) {
            repository.markSkipped(id, "Auto-reply disabled")
            ActivityLogStore.add("SKIPPED", "Message skipped for ${message.conversationDisplayName}: auto-reply disabled")
            return
        }

        // Global pause via /.all — do not process any messages
        if (settings.globalPauseEnabled) {
            repository.markSkipped(id, "Global pause active")
            ActivityLogStore.add("SKIPPED", "Message skipped for ${message.conversationDisplayName}: global pause active")
            return
        }

        // Individual conversation pause via /.this — do not process messages for this conversation
        // Unless it's an explicit exception during global pause
        if (chatControl.isPaused(message.conversationKey) && !chatControl.isException(message.conversationKey)) {
            repository.markSkipped(id, "Conversation paused")
            ActivityLogStore.add("SKIPPED", "Message skipped for ${message.conversationDisplayName}: conversation paused")
            return
        }

        // Check if message has been superseded by owner reply
        if (message.superseded || supersededTracker.isSuperseded(message.messageId)) {
            repository.markSuperseded(id, "Superseded by owner reply before processing")
            ActivityLogStore.add("SKIPPED", "Message skipped for ${message.conversationDisplayName}: superseded by owner reply")
            return
        }

        val ignored = settings.ignoredSendersCsv
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotBlank() }
        if (ignored.any { message.actualSender.lowercase().contains(it) }) {
            repository.markSkipped(id, "Ignored sender: ${message.actualSender}")
            ActivityLogStore.add("SKIPPED", "Ignored sender: ${message.actualSender} in ${message.conversationDisplayName}")
            return
        }

        repository.updateStatus(
            id = id,
            status = "PROCESSING",
            retryCount = message.retryCount
        )
        ActivityLogStore.add("PROCESSING", "Generating AI reply for ${message.actualSender} in ${message.conversationDisplayName}")

        // Retrieve previous 3 messages from conversation history for AI context
        val contextMessages = historyRepository.getPreviousMessages(
            conversationKey = message.conversationKey,
            limit = 3,
            beforeTimestampMs = message.originalTimestampMs
        )
        val recentHistory = contextMessages.map { entry ->
            val prefix = when (entry.direction) {
                MessageDirection.OUTGOING -> "ME: "
                MessageDirection.INCOMING -> "THEM: "
                MessageDirection.UNCERTAIN -> "UNKNOWN: "
            }
            "$prefix${entry.messageText}"
        }

        val replyResult = try {
            val reply = replyGenerator.generateReply(
                settings = settings,
                sender = message.actualSender,
                incomingMessage = message.incomingMessageText,
                recentHistory = recentHistory
            )
            null to reply
        } catch (e: Exception) {
            e to null
        }

        if (replyResult.second != null) {
            val replyText = replyResult.second!!
            deliverReply(message, replyText)
        } else {
            val error = replyResult.first!!
            handleGenerationError(message, error)
        }
    }

    private fun deliverReply(message: QueuedMessage, replyText: String) {
        val notification = findLatestNotificationForConversation(message.packageName, message.conversationKey)
        val remoteInputAction = notification?.let { findRemoteInputAction(it) }

        if (remoteInputAction == null) {
            repository.updateStatus(
                id = message.id,
                status = "WAITING_FOR_NOTIFICATION",
                retryCount = message.retryCount,
                nextRetryTimeMs = System.currentTimeMillis() + 30_000,
                lastError = "RemoteInput action not available for conversation: ${message.conversationDisplayName}"
            )
            ActivityLogStore.add("WAITING_FOR_NOTIFICATION", "RemoteInput expired for ${message.conversationDisplayName}")
            return
        }

        val currentSettings = settingsRepository.settings.first()
        if (!currentSettings.autoReplyEnabled) {
            repository.markSkipped(message.id, "Auto-reply disabled before sending")
            ActivityLogStore.add("SKIPPED", "Auto-reply disabled before sending to ${message.actualSender}")
            return
        }

        // Re-check pauses before delivery — conversation may have been paused while AI request was running
        if (currentSettings.globalPauseEnabled && !chatControl.isException(message.conversationKey)) {
            repository.markSkipped(message.id, "Global pause active before sending")
            ActivityLogStore.add("SKIPPED", "Reply not sent to ${message.actualSender}: global pause active")
            return
        }
        if (chatControl.isPaused(message.conversationKey) && !chatControl.isException(message.conversationKey)) {
            repository.markSkipped(message.id, "Conversation paused before sending")
            ActivityLogStore.add("SKIPPED", "Reply not sent to ${message.actualSender}: conversation paused")
            return
        }

        // Re-check superseded before delivery — owner may have replied while AI request was running
        if (message.superseded || supersededTracker.isSuperseded(message.messageId)) {
            repository.markSuperseded(message.id, "Superseded by owner reply before delivery")
            ActivityLogStore.add("SKIPPED", "Reply not sent to ${message.actualSender}: superseded by owner reply")
            return
        }

        try {
            val fillInIntent = Intent()
            val results = Bundle()
            remoteInputAction.remoteInputs?.forEach { input ->
                results.putCharSequence(input.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputAction.remoteInputs, fillInIntent, results)
            remoteInputAction.actionIntent.send(context, 0, fillInIntent)

            repository.markSent(message.id)
            ActivityLogStore.add("SENT", "Auto-replied to ${message.actualSender} in ${message.conversationDisplayName}")
        } catch (e: Exception) {
            repository.updateStatus(
                id = message.id,
                status = "WAITING_FOR_NOTIFICATION",
                retryCount = message.retryCount,
                nextRetryTimeMs = System.currentTimeMillis() + 30_000,
                lastError = "Failed to send RemoteInput: ${e.message ?: e.javaClass.simpleName}"
            )
            ActivityLogStore.add("WAITING_FOR_NOTIFICATION", "RemoteInput send failed for ${message.conversationDisplayName}")
        }
    }

    private fun handleGenerationError(message: QueuedMessage, error: Exception) {
        val errorMsg = error.message ?: error.javaClass.simpleName
        val now = System.currentTimeMillis()
        val newRetryCount = message.retryCount + 1

        val isPermanent = RetryPolicy.isPermanentError(errorMsg) || newRetryCount >= RetryPolicy.MAX_RETRIES

        if (isPermanent) {
            repository.markFailed(message.id, "Permanent error after $newRetryCount attempts: $errorMsg")
            ActivityLogStore.add("FAILED", "${message.conversationDisplayName}: $errorMsg (retries: $newRetryCount)")
            return
        }

        val nextRetryMs = RetryPolicy.calculateRetryDelay(newRetryCount, errorMsg)

        repository.updateStatus(
            id = message.id,
            status = "RETRY_WAIT",
            retryCount = newRetryCount,
            nextRetryTimeMs = now + nextRetryMs,
            lastError = errorMsg
        )

        val delaySeconds = (nextRetryMs / 1000.0).roundToInt()
        ActivityLogStore.add("RETRY_SCHEDULED", "${message.conversationDisplayName}: retry in ${delaySeconds}s (attempt $newRetryCount): $errorMsg")
    }

    private fun findLatestNotificationForConversation(
        packageName: String,
        conversationKey: String
    ): StatusBarNotification? {
        return try {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val notifications = notificationManager.getActiveNotifications()
            notifications
                .filter { it.packageName == packageName }
                .maxByOrNull { it.postTime }
        } catch (e: Exception) {
            ActivityLogStore.add("ERROR", "Failed to query active notifications: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    private fun findRemoteInputAction(sbn: StatusBarNotification): Action? {
        return sbn.notification?.actions
            ?.firstOrNull { candidate -> candidate.remoteInputs?.isNotEmpty() == true }
    }

    fun enqueue(message: QueuedMessage): Long {
        return repository.enqueue(message)
    }

    /**
     * Enqueue a message for manual old-message response (/.old command).
     * Bypasses normal cooldown since this is an explicit user request.
     * Uses CONFLICT_IGNORE to prevent duplicate execution.
     */
    fun enqueueOldMessage(message: QueuedMessage): Long {
        return repository.enqueue(message)
    }

    fun wakeUp() {
        // The polling loop checks every 5 seconds
    }
}