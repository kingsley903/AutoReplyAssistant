package com.example.autoreplyassistant.service

import com.example.autoreplyassistant.data.QueuedMessage
import org.junit.Test
import org.junit.Assert.*

class RetryLogicTest {

    @Test
    fun `http 429 is retriable`() {
        val errorMsg = "AI endpoint returned HTTP 429: Too Many Requests"
        assertFalse(RetryPolicy.isPermanentError(errorMsg))
    }

    @Test
    fun `http 500 is retriable`() {
        val errorMsg = "AI endpoint returned HTTP 500: Internal Server Error"
        assertFalse(RetryPolicy.isPermanentError(errorMsg))
    }

    @Test
    fun `http 503 is retriable`() {
        val errorMsg = "AI endpoint returned HTTP 503: Service Unavailable"
        assertFalse(RetryPolicy.isPermanentError(errorMsg))
    }

    @Test
    fun `http 401 is permanent (invalid API key)`() {
        val errorMsg = "AI endpoint returned HTTP 401: Unauthorized"
        assertTrue(RetryPolicy.isPermanentError(errorMsg))
    }

    @Test
    fun `http 403 is permanent (authorization error)`() {
        val errorMsg = "AI endpoint returned HTTP 403: Forbidden"
        assertTrue(RetryPolicy.isPermanentError(errorMsg))
    }

    @Test
    fun `http 400 is permanent (config error)`() {
        val errorMsg = "AI endpoint returned HTTP 400: Bad Request"
        assertTrue(RetryPolicy.isPermanentError(errorMsg))
    }

    @Test
    fun `api key not configured is permanent`() {
        val errorMsg = "API key is not configured."
        assertTrue(RetryPolicy.isPermanentError(errorMsg))
    }

    @Test
    fun `model name empty is permanent`() {
        val errorMsg = "Model name is empty."
        assertTrue(RetryPolicy.isPermanentError(errorMsg))
    }

    @Test
    fun `network error is retriable`() {
        val errorMsg = "Connection refused"
        assertFalse(RetryPolicy.isPermanentError(errorMsg))
    }

    @Test
    fun `retry after header is parsed`() {
        val errorMsg = "AI endpoint returned HTTP 429: Too Many Requests Retry-After: 60"
        val delay = RetryPolicy.calculateRetryDelay(1, errorMsg)
        assertEquals(60_000L, delay)
    }

    @Test
    fun `exponential backoff increases with retry count`() {
        val errorMsg = "Connection timed out"
        val delay1 = RetryPolicy.calculateRetryDelay(1, errorMsg)
        val delay2 = RetryPolicy.calculateRetryDelay(2, errorMsg)
        val delay3 = RetryPolicy.calculateRetryDelay(3, errorMsg)
        assertTrue(delay2 > delay1)
        assertTrue(delay3 > delay2)
    }

    @Test
    fun `backoff has minimum of 10 seconds`() {
        val errorMsg = "Network unavailable"
        val delay = RetryPolicy.calculateRetryDelay(1, errorMsg)
        assertTrue(delay >= 10_000L)
    }

    @Test
    fun `backoff is capped at 30 minutes`() {
        val errorMsg = "Connection timed out"
        val delay = RetryPolicy.calculateRetryDelay(10, errorMsg)
        assertTrue(delay <= 30 * 60 * 1000L)
    }

    @Test
    fun `max retries constant is 5`() {
        assertEquals(5, RetryPolicy.MAX_RETRIES)
    }

    @Test
    fun `queued message defaults to pending`() {
        val msg = QueuedMessage(
            messageId = "test-1",
            packageName = "com.whatsapp",
            conversationKey = "com.whatsapp|Alice",
            conversationDisplayName = "Alice",
            actualSender = "Alice",
            incomingMessageText = "Hello",
            originalTimestampMs = 1234567890
        )
        assertEquals("PENDING", msg.status)
        assertEquals(0, msg.retryCount)
        assertEquals(0L, msg.nextRetryTimeMs)
        assertNull(msg.lastError)
    }
}