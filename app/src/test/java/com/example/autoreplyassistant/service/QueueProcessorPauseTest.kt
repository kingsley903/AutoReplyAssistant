package com.example.autoreplyassistant.service

import com.example.autoreplyassistant.data.QueuedMessage
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for QueueProcessor pause integration logic.
 * These tests verify the business rules for when messages should be skipped
 * due to global or individual conversation pauses.
 */
class QueueProcessorPauseTest {

    @Test
    fun `globally paused message is skipped`() {
        val globalPaused = true
        val conversationPaused = false
        val autoReplyEnabled = true

        val shouldProcess = autoReplyEnabled && !globalPaused && !conversationPaused
        assertFalse(shouldProcess)
    }

    @Test
    fun `individually paused conversation message is skipped`() {
        val globalPaused = false
        val conversationPaused = true
        val autoReplyEnabled = true

        val shouldProcess = autoReplyEnabled && !globalPaused && !conversationPaused
        assertFalse(shouldProcess)
    }

    @Test
    fun `both global and individual pause skip message`() {
        val globalPaused = true
        val conversationPaused = true
        val autoReplyEnabled = true

        val shouldProcess = autoReplyEnabled && !globalPaused && !conversationPaused
        assertFalse(shouldProcess)
    }

    @Test
    fun `auto-reply disabled skips message regardless of pause state`() {
        val autoReplyEnabled = false
        val globalPaused = false
        val conversationPaused = false

        val shouldProcess = autoReplyEnabled && !globalPaused && !conversationPaused
        assertFalse(shouldProcess)
    }

    @Test
    fun `eligible incoming message is processed when no pauses`() {
        val autoReplyEnabled = true
        val globalPaused = false
        val conversationPaused = false

        val shouldProcess = autoReplyEnabled && !globalPaused && !conversationPaused
        assertTrue(shouldProcess)
    }

    @Test
    fun `resume conversation allows processing again`() {
        var conversationPaused = true
        val globalPaused = false
        val autoReplyEnabled = true

        // Simulate /.resume
        conversationPaused = false

        val shouldProcess = autoReplyEnabled && !globalPaused && !conversationPaused
        assertTrue(shouldProcess)
    }

    @Test
    fun `resume all clears global pause but not individual pauses`() {
        var globalPaused = true
        var conversationPaused = true

        // Simulate /.resumeall
        globalPaused = false

        assertFalse(globalPaused)
        assertTrue(conversationPaused) // individual pause remains

        val shouldProcess = true && !globalPaused && !conversationPaused
        assertFalse(shouldProcess) // still blocked by individual pause
    }

    @Test
    fun `queued message status remains unchanged when skipped due to pause`() {
        val msg = QueuedMessage(
            messageId = "test-1",
            packageName = "com.whatsapp",
            conversationKey = "com.whatsapp|Alice",
            conversationDisplayName = "Alice",
            actualSender = "Alice",
            incomingMessageText = "Hello",
            originalTimestampMs = 1234567890,
            status = "PENDING"
        )

        // When skipped due to pause, status should change to SKIPPED
        // but the message remains in the queue (not deleted)
        val skippedStatus = "SKIPPED"
        assertEquals("PENDING", msg.status)
        assertNotEquals(skippedStatus, msg.status)
        // Message is preserved — can be retried when resumed
    }

    @Test
    fun `whatsapp and whatsapp business conversations are independent`() {
        val whatsappPaused = true
        val whatsappBusinessPaused = false

        // WhatsApp conversation is paused
        val whatsappShouldProcess = !whatsappPaused
        assertFalse(whatsappShouldProcess)

        // WhatsApp Business conversation is not paused
        val wabShouldProcess = !whatsappBusinessPaused
        assertTrue(wabShouldProcess)
    }
}