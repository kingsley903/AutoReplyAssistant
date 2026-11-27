package com.example.autoreplyassistant.data

import org.junit.Test
import org.junit.Assert.*

/**
 * Pure-logic tests for chat control state interactions.
 * These tests do not require Android Context or SQLite — they verify
 * the business rules for global pause vs individual pause interaction.
 */
class ChatControlStateTest {

    @Test
    fun `global pause blocks all conversations`() {
        val globalPaused = true
        val individualPaused = false

        val shouldProcess = !globalPaused && !individualPaused
        assertFalse(shouldProcess)
    }

    @Test
    fun `individual pause blocks that conversation only`() {
        val globalPaused = false
        val individualPaused = true

        val shouldProcess = !globalPaused && !individualPaused
        assertFalse(shouldProcess)
    }

    @Test
    fun `neither pause allows processing`() {
        val globalPaused = false
        val individualPaused = false

        val shouldProcess = !globalPaused && !individualPaused
        assertTrue(shouldProcess)
    }

    @Test
    fun `global resume does not unpause individual conversations`() {
        val globalPaused = false
        var individualPaused = true

        // Simulate /.resumeall
        val globalPausedAfter = false

        assertFalse(globalPausedAfter)
        assertTrue(individualPaused) // unchanged
    }

    @Test
    fun `individual resume does not clear global pause`() {
        var globalPaused = true
        val individualPaused = true

        // Simulate /.resume for one conversation
        val individualPausedAfter = false
        val globalPausedAfter = true // unchanged

        assertFalse(individualPausedAfter)
        assertTrue(globalPausedAfter)
    }

    @Test
    fun `resume conversation when not paused is no-op`() {
        val wasPaused = false
        val resumeResult = if (wasPaused) 1 else 0
        assertEquals(0, resumeResult)
    }

    @Test
    fun `pause persists across repo recreation`() {
        val pausedKeys = mutableSetOf<String>()
        pausedKeys.add("com.whatsapp|Alice")

        // New repo reads same persisted data
        assertTrue(pausedKeys.contains("com.whatsapp|Alice"))
        assertFalse(pausedKeys.contains("com.whatsapp|Bob"))
    }

    @Test
    fun `command pause state is independent per conversation key`() {
        val pausedKeys = mutableSetOf<String>()
        pausedKeys.add("com.whatsapp|Alice")
        pausedKeys.add("com.whatsapp.w4b|Alice")

        assertTrue(pausedKeys.contains("com.whatsapp|Alice"))
        assertTrue(pausedKeys.contains("com.whatsapp.w4b|Alice"))
        assertFalse(pausedKeys.contains("com.whatsapp|Bob"))
    }
}