package com.chloemlla.aura.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Bounded backoff for HTTP 429 responses, scoped to specific hosts.
 *
 * Freesound v2 enforces a 60 req/min token-bucket per IP and emits Retry-After
 * on 429. Without this interceptor those failures bubble up as generic
 * HttpException and the Sounds tab silently goes blank for ~60s. With this
 * interceptor a transient burst recovers without surfacing an error.
 *
 * Scope is intentionally narrow: only requests whose URL host ends with one of
 * [hostSuffixes] are retried. We don't want to introduce surprise latency on
 * Wallhaven, Reddit, etc.
 *
 * @param hostSuffixes lowercase host suffixes that opt into 429-aware retries
 *   (e.g. "freesound.org"). Match is "host == suffix || host endsWith ".$suffix"".
 * @param maxRetries upper bound on retries for a single request (default 2).
 *   Total wall-clock latency is bounded by maxRetries * retryCeilingMs.
 * @param defaultBackoffMs delay used when the response omits Retry-After.
 * @param retryCeilingMs upper bound on any single retry wait — clamps a
 *   pathological "Retry-After: 99999" so we don't stall the app.
 */
class RateLimitInterceptor(
    private val hostSuffixes: Set<String>,
    private val maxRetries: Int = 2,
    private val defaultBackoffMs: Long = 1_500L,
    private val retryCeilingMs: Long = 30_000L,
    private val sleeper: (Long) -> Unit = ::interruptibleSleep,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!hostMatches(request.url.host)) {
            return chain.proceed(request)
        }

        var attempt = 0
        var response: Response = chain.proceed(request)
        while (response.code == 429 && attempt < maxRetries) {
            val waitMs = parseRetryAfterMs(response.header("Retry-After"))
                ?: defaultBackoffMs
            val clamped = min(retryCeilingMs, max(0L, waitMs))
            // Close current response BEFORE we sleep so the connection can be
            // reused / pooled rather than held idle for the entire backoff.
            response.close()
            sleeper(clamped)
            attempt++
            response = chain.proceed(request)
        }
        return response
    }

    private fun hostMatches(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT)
        return hostSuffixes.any { suffix ->
            h == suffix || h.endsWith(".$suffix")
        }
    }

    /**
     * Retry-After is RFC 7231 5.1 — either delta-seconds (int) or HTTP-date.
     * We only honor delta-seconds; HTTP-date support is overkill for the
     * services we target (Freesound returns delta-seconds).
     */
    private fun parseRetryAfterMs(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val seconds = header.trim().toLongOrNull() ?: return null
        if (seconds < 0) return null
        return TimeUnit.SECONDS.toMillis(seconds)
    }
}

/**
 * Interruptible bounded wait used by [RateLimitInterceptor]'s default backoff.
 *
 * A bare `Thread.sleep` pins the dispatcher thread for the full wait with no way
 * for a cancelled caller to cut it short. This loop instead checks the interrupt
 * flag before every wait segment, exits early when the thread is interrupted, and
 * restores the flag so the interruption is never lost. It is resilient to spurious
 * wakeups (it re-waits for the remaining time) and never sleeps past [ms].
 */
private fun interruptibleSleep(ms: Long) {
    if (ms <= 0) return
    val monitor = Object()
    synchronized(monitor) {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ms)
        while (true) {
            if (Thread.currentThread().isInterrupted) {
                // Restore the flag: Object.wait would have cleared it on throw.
                Thread.currentThread().interrupt()
                return
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return
            try {
                monitor.wait(
                    TimeUnit.NANOSECONDS.toMillis(remainingNanos),
                    (remainingNanos % 1_000_000L).toInt(),
                )
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return
            }
        }
    }
}
