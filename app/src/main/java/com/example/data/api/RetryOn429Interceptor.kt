package com.example.data.api

import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import java.io.IOException

/**
 * Retries requests that Azure DevOps throttled (429) or that hit a temporarily unavailable
 * server (503), honouring the `Retry-After` header when present and otherwise backing off
 * exponentially (1 s, 2 s, ...).
 *
 * Only safe-to-repeat requests are retried: GET, and the WIQL query POST (a read-only query).
 * Updates and creates (PATCH / other POSTs) are never retried, so a change is never sent twice.
 *
 * @param sleeper waits the given number of milliseconds; replaceable in tests.
 */
class RetryOn429Interceptor(
    private val maxRetries: Int = 2,
    private val maxWaitMs: Long = 10_000L,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) }
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var response = chain.proceed(request)
        if (!isSafeToRetry(request)) return response

        var attempt = 0
        while (attempt < maxRetries && response.code in RETRYABLE_CODES && !chain.call().isCanceled()) {
            val waitMs = retryDelayMs(response.header("Retry-After"), attempt)
            response.close()
            try {
                sleeper(waitMs)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while waiting to retry a throttled request", e)
            }
            attempt++
            response = chain.proceed(request)
        }
        return response
    }

    private fun isSafeToRetry(request: Request): Boolean = when (request.method) {
        "GET" -> true
        "POST" -> request.url.encodedPath.endsWith("/_apis/wit/wiql")
        else -> false
    }

    /** `Retry-After` in seconds when given (HTTP-date values fall back to backoff), capped. */
    internal fun retryDelayMs(retryAfterHeader: String?, attempt: Int): Long {
        val retryAfterSeconds = retryAfterHeader?.trim()?.toLongOrNull()
        val delayMs = if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
            retryAfterSeconds * 1000L
        } else {
            BACKOFF_BASE_MS shl attempt.coerceAtMost(MAX_BACKOFF_SHIFT)
        }
        return delayMs.coerceIn(0L, maxWaitMs)
    }

    private companion object {
        val RETRYABLE_CODES = setOf(429, 503)
        const val BACKOFF_BASE_MS = 1_000L
        const val MAX_BACKOFF_SHIFT = 10
    }
}
