package com.example.autoreplyassistant.data

import org.junit.Test
import org.junit.Assert.*

class ActivityLogStoreTest {

    @Test
    fun `clearLogs empties the log items`() {
        ActivityLogStore.add("TEST_EVENT", "First log item")
        ActivityLogStore.add("TEST_EVENT_2", "Second log item")
        assertTrue(ActivityLogStore.items.value.isNotEmpty())

        ActivityLogStore.clearLogs()

        assertTrue(ActivityLogStore.items.value.isEmpty())
        assertEquals(0, ActivityLogStore.items.value.size)
    }

    @Test
    fun `new events can be added after clearLogs`() {
        ActivityLogStore.add("EVENT_1", "Before clear")
        ActivityLogStore.clearLogs()
        assertEquals(0, ActivityLogStore.items.value.size)

        ActivityLogStore.add("EVENT_AFTER", "After clear")
        assertEquals(1, ActivityLogStore.items.value.size)
        assertEquals("EVENT_AFTER", ActivityLogStore.items.value[0].status)
        assertEquals("After clear", ActivityLogStore.items.value[0].details)
    }
}
