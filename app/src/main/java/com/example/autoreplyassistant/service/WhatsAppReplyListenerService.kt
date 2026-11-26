package com.example.autoreplyassistant.service

import android.app.Notification
import android.app.Notification.Action
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.example.autoreplyassistant.data.ActivityLogStore
import com.example.autoreplyassistant.data.SettingsRepository
import com.example.autoreplyassistant.network.ReplyGenerator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class WhatsAppReplyListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var settingsRepository: SettingsRepository
    private val replyGenerator = ReplyGenerator()

    private val recentlyHandled = ConcurrentHashMap.newKeySet<String>()
    private val senderLastReplyAt = ConcurrentHashMap<String, Long>()
    private val history = object : android.util.LruCache<String, MutableList<String>>(100) {}

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName !in ALLOWED_PACKAGES) return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val sender = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (sender.isBlank() || body.isBlank()) return

        val action = notification.actions
            ?.firstOrNull { candidate -> candidate.remoteInputs?.isNotEmpty() == true }
            ?: return
        val remoteInputs = action.remoteInputs ?: return
        val messageKey = "${sbn.packageName}|${sender.lowercase()}|${body.hashCode()}|${sbn.postTime}"
        if (!recentlyHandled.add(messageKey)) return
        trimHandledKeys()

        serviceScope.launch {
            try {
                val settings = settingsRepository.settings.first()
                if (!settings.autoReplyEnabled) return@launch

                val ignored = settings.ignoredSendersCsv
                    .split(",")
                    .map { it.trim().lowercase() }
                    .filter { it.isNotBlank() }
                if (ignored.any { sender.lowercase().contains(it) }) {
                    ActivityLogStore.add("SKIPPED", "Ignored sender: $sender")
                    return@launch
                }

                val now = System.currentTimeMillis()
                val last = senderLastReplyAt[sender.lowercase()] ?: 0L
                if (now - last < MIN_SENDER_COOLDOWN_MS) {
                    ActivityLogStore.add("SKIPPED", "Cooldown active for $sender")
                    return@launch
                }

                val previous = synchronized(history) {
                    history.get(sender.lowercase())?.toList().orEmpty()
                }
                val reply = replyGenerator.generateReply(settings, sender, body, previous)
                if (reply.isBlank()) {
                    ActivityLogStore.add("ERROR", "No reply generated for $sender")
                    return@launch
                }

                delay(settings.replyDelayMs.coerceIn(0L, 60_000L))
                val currentSettings = settingsRepository.settings.first()
                if (!currentSettings.autoReplyEnabled) {
                    ActivityLogStore.add("CANCELLED", "Auto-reply disabled before sending to $sender")
                    return@launch
                }

                val fillInIntent = Intent()
                val results = Bundle()
                remoteInputs.forEach { input -> results.putCharSequence(input.resultKey, reply) }
                RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, results)
                action.actionIntent.send(this@WhatsAppReplyListenerService, 0, fillInIntent)

                senderLastReplyAt[sender.lowercase()] = System.currentTimeMillis()
                synchronized(history) {
                    val contactHistory = history.get(sender.lowercase()) ?: mutableListOf()
                    contactHistory.add("THEM: $body")
                    contactHistory.add("ME: $reply")
                    while (contactHistory.size > 5) contactHistory.removeAt(0)
                    history.put(sender.lowercase(), contactHistory)
                }
                ActivityLogStore.add("SENT", "Auto-replied to $sender")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                ActivityLogStore.add("ERROR", "Reply to $sender failed: ${error.message ?: error.javaClass.simpleName}")
            }
        }
    }

    private fun trimHandledKeys() {
        if (recentlyHandled.size > 500) {
            recentlyHandled.clear()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private val ALLOWED_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")
        private const val MIN_SENDER_COOLDOWN_MS = 5_000L
    }
}
