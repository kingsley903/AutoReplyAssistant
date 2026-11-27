package com.example.autoreplyassistant.service

import com.example.autoreplyassistant.data.MessageDirection
import org.junit.Test
import org.junit.Assert.*

class DirectionDetectionTest {

    @Test
    fun `incoming individual message is detected as incoming`() {
        val direction = NotificationParser.determineDirection(
            latestSender = "Alice",
            ownerName = "Bob",
            isGroup = false,
            distinctSenders = setOf("Alice")
        )
        assertEquals(MessageDirection.INCOMING, direction)
    }

    @Test
    fun `outgoing individual message is detected as outgoing`() {
        val direction = NotificationParser.determineDirection(
            latestSender = "Bob",
            ownerName = "Bob",
            isGroup = false,
            distinctSenders = setOf("Bob")
        )
        assertEquals(MessageDirection.OUTGOING, direction)
    }

    @Test
    fun `incoming group message identifies actual member`() {
        val direction = NotificationParser.determineDirection(
            latestSender = "Member A",
            ownerName = "Bob",
            isGroup = true,
            distinctSenders = setOf("Member A", "Member B", "Bob")
        )
        assertEquals(MessageDirection.INCOMING, direction)
    }

    @Test
    fun `outgoing group message is detected as outgoing`() {
        val direction = NotificationParser.determineDirection(
            latestSender = "Bob",
            ownerName = "Bob",
            isGroup = true,
            distinctSenders = setOf("Member A", "Bob")
        )
        assertEquals(MessageDirection.OUTGOING, direction)
    }

    @Test
    fun `uncertain when owner name not configured`() {
        val direction = NotificationParser.determineDirection(
            latestSender = "Alice",
            ownerName = "",
            isGroup = false,
            distinctSenders = setOf("Alice")
        )
        assertEquals(MessageDirection.UNCERTAIN, direction)
    }

    @Test
    fun `uncertain when owner name is blank`() {
        val direction = NotificationParser.determineDirection(
            latestSender = "Alice",
            ownerName = "   ",
            isGroup = false,
            distinctSenders = setOf("Alice")
        )
        assertEquals(MessageDirection.UNCERTAIN, direction)
    }

    @Test
    fun `case insensitive owner comparison`() {
        val direction = NotificationParser.determineDirection(
            latestSender = "BOB",
            ownerName = "bob",
            isGroup = false,
            distinctSenders = setOf("BOB")
        )
        assertEquals(MessageDirection.OUTGOING, direction)
    }

    @Test
    fun `whitespace trimmed before comparison`() {
        val direction = NotificationParser.determineDirection(
            latestSender = "  Bob  ",
            ownerName = "Bob",
            isGroup = false,
            distinctSenders = setOf("Bob")
        )
        assertEquals(MessageDirection.OUTGOING, direction)
    }
}