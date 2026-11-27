package com.example.autoreplyassistant.service

import com.example.autoreplyassistant.data.MessageDirection
import org.junit.Test
import org.junit.Assert.*

/**
 * Tests for WhatsApp command detection logic.
 * Verifies commands are only recognized when:
 * - Direction is OUTGOING (owner-authored)
 * - Message exactly matches a command (case-insensitive, trimmed)
 * - Direction is not UNCERTAIN
 */
class CommandDetectionTest {

    private val COMMANDS = setOf("/.this", "/.all", "/.resume", "/.resumeall")

    private fun isCommand(text: String, direction: MessageDirection, ownerName: String): Boolean {
        if (direction != MessageDirection.OUTGOING || ownerName.isBlank()) return false
        val trimmed = text.trim()
        return COMMANDS.contains(trimmed.lowercase())
    }

    @Test
    fun `owner sends /.this in individual chat is recognized`() {
        assertTrue(isCommand("/.this", MessageDirection.OUTGOING, "Bob"))
    }

    @Test
    fun `owner sends /.all in group chat is recognized`() {
        assertTrue(isCommand("/.all", MessageDirection.OUTGOING, "Bob"))
    }

    @Test
    fun `owner sends /.resume is recognized`() {
        assertTrue(isCommand("/.resume", MessageDirection.OUTGOING, "Bob"))
    }

    @Test
    fun `owner sends /.resumeall is recognized`() {
        assertTrue(isCommand("/.resumeall", MessageDirection.OUTGOING, "Bob"))
    }

    @Test
    fun `command matching is case insensitive`() {
        assertTrue(isCommand("/.THIS", MessageDirection.OUTGOING, "Bob"))
        assertTrue(isCommand("/.All", MessageDirection.OUTGOING, "Bob"))
        assertTrue(isCommand("/.RESUME", MessageDirection.OUTGOING, "Bob"))
        assertTrue(isCommand("/.RESUMEALL", MessageDirection.OUTGOING, "Bob"))
    }

    @Test
    fun `command with surrounding whitespace is recognized after trim`() {
        assertTrue(isCommand("  /.this  ", MessageDirection.OUTGOING, "Bob"))
        assertTrue(isCommand("\t/.all\t", MessageDirection.OUTGOING, "Bob"))
    }

    @Test
    fun `contact sending /.this is not a command (incoming)`() {
        assertFalse(isCommand("/.this", MessageDirection.INCOMING, "Bob"))
    }

    @Test
    fun `group member sending /.all is not a command (incoming)`() {
        assertFalse(isCommand("/.all", MessageDirection.INCOMING, "Bob"))
    }

    @Test
    fun `uncertain direction never executes command`() {
        assertFalse(isCommand("/.this", MessageDirection.UNCERTAIN, "Bob"))
    }

    @Test
    fun `no owner name means never executes command`() {
        assertFalse(isCommand("/.this", MessageDirection.OUTGOING, ""))
        assertFalse(isCommand("/.this", MessageDirection.OUTGOING, "   "))
    }

    @Test
    fun `ordinary sentence containing command text is not a command`() {
        assertFalse(isCommand("Please read /.this later", MessageDirection.OUTGOING, "Bob"))
        assertFalse(isCommand("/.this is the command", MessageDirection.OUTGOING, "Bob"))
        assertFalse(isCommand("say /.all please", MessageDirection.OUTGOING, "Bob"))
    }

    @Test
    fun `partial command match is not recognized`() {
        assertFalse(isCommand("/.thisx", MessageDirection.OUTGOING, "Bob"))
        assertFalse(isCommand("x/.all", MessageDirection.OUTGOING, "Bob"))
        assertFalse(isCommand("/.resumeextra", MessageDirection.OUTGOING, "Bob"))
    }

    @Test
    fun `empty message is not a command`() {
        assertFalse(isCommand("", MessageDirection.OUTGOING, "Bob"))
        assertFalse(isCommand("   ", MessageDirection.OUTGOING, "Bob"))
    }

    @Test
    fun `non-command message is not recognized`() {
        assertFalse(isCommand("Hello", MessageDirection.OUTGOING, "Bob"))
        assertFalse(isCommand("How are you?", MessageDirection.OUTGOING, "Bob"))
        assertFalse(isCommand("/help", MessageDirection.OUTGOING, "Bob"))
    }
}