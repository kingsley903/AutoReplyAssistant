package com.example.autoreplyassistant.data

import org.junit.Test
import org.junit.Assert.*

class MessageDirectionTest {

    @Test
    fun `values include incoming outgoing and uncertain`() {
        assertEquals(3, MessageDirection.values().size)
        assertTrue(MessageDirection.INCOMING in listOf(*MessageDirection.values()))
        assertTrue(MessageDirection.OUTGOING in listOf(*MessageDirection.values()))
        assertTrue(MessageDirection.UNCERTAIN in listOf(*MessageDirection.values()))
    }

    @Test
    fun `directions are distinct`() {
        assertNotEquals(MessageDirection.INCOMING, MessageDirection.OUTGOING)
        assertNotEquals(MessageDirection.INCOMING, MessageDirection.UNCERTAIN)
        assertNotEquals(MessageDirection.OUTGOING, MessageDirection.UNCERTAIN)
    }
}