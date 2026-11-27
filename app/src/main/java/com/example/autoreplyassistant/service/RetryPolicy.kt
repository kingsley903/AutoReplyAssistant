package com.example.autoreplyassistant.service

import java.io.IOException

/**
 * Centralised retry policy for OpenRouter AI API calls.
 *
 * Determines whether an error is permanent (no retry) or temporary (retry with backoff),
 * parses Retry-After hints, and calculates exponential backoff with jitter.
 */
object RetryPolicy {

    /** Maximum number of retry attempts before a message is marked FAILED. */
    const val MAX_RETRIES = 5

    /** Base delay in milliseconds for exponential backoff. */
    private const val BASE_DELAY_MS = 30_000L

    /** Maximum delay cap in milliseconds (30 minutes). */
    private const val MAX_DELAY_MS = 30L * 60 * 1000

    /** Minimum delay in milliseconds between retries. */
    private const val MIN_DELAY_MS = 10_000L

    /**
     * Returns true if the error should NOT be retried.
     *
     * Permanent errors: HTTP 401, 403, 400, missing API key, empty model name.
     * Temporary errors: HTTP 429, 5xx, network errors, timeouts, empty responses.
     */
    fun isPermanentError(errorMsg: String): Boolean {
        val lower = errorMsg.lowercase()
        return lower.contains("http 401") ||
            lower.contains("http 403") ||
            lower.contains("http 400") ||
            lower.contains("api key is not configured") ||
            lower.contains("model name is empty")
    }

    /**
     * Calculates the delay in milliseconds before the next retry attempt.
     *
     * If a Retry-After value is found in the error message, it is respected.
     * Otherwise exponential backoff with jitter is used.
     */
    fun calculateRetryDelay(retryCount: Int, errorMsg: String): Long {
        val lower = errorMsg.lowercase()
        val retryAfterMatch = Regex("retry.?after.?[:=]?\\s*(\\d+)").find(lower)
        if (retryAfterMatch != null) {
            val seconds = retryAfterMatch.groupValues[1].toLongOrNull() ?: 60
            return seconds * 1000
        }

        val exponential = BASE_DELAY_MS * (1L shl (retryCount - 1).coerceAtMost(5))
        val capped = exponential.coerceAtMost(MAX_DELAY_MS)

        val jitterRange = capped / 4
        val jitter = (Math.random() * 2 * jitterRange - jitterRange).toLong()
        return (capped + jitter).coerceAtLeast(MIN_DELAY_MS)
    }
}