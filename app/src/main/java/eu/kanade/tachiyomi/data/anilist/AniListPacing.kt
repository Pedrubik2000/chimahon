package eu.kanade.tachiyomi.data.anilist

import okhttp3.Interceptor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * How fast the app talks to AniList. AniList allows 30 requests a minute at present (90 when it is not degraded) and
 * answers 429 with a Retry-After (seconds) past that. The AniList client's limiter takes [PER_MINUTE]; a 429 sets
 * [blockedUntil], which the client waits out before its next request ([interceptor]) and adoption waits out before its
 * next pass ([waitBeforePass]).
 */
object AniListPacing {

    /** Under AniList's 30 with room for the few AniList calls that use other clients (import search, colours). */
    const val PER_MINUTE = 28

    /** Without a usable Retry-After: AniList's minute plus a second. */
    const val DEFAULT_WAIT = 61_000L
    private const val MAX_WAIT = 5 * 60_000L

    /** Epoch millis before which no AniList request should go out: set by the last 429. */
    @Volatile
    var blockedUntil = 0L

    /** A 429's Retry-After (seconds) → millis to wait; missing or unreadable (an HTTP date) → [DEFAULT_WAIT]. */
    fun retryAfterMillis(header: String?): Long =
        header?.trim()?.toLongOrNull()?.let { (it * 1000).coerceIn(1000, MAX_WAIT) } ?: DEFAULT_WAIT

    /** Millis before adoption try [attempt] (0 = first): a 429's Retry-After first, else a minute between tries. */
    fun waitBeforePass(attempt: Int, blockedUntil: Long, now: Long): Long = when {
        blockedUntil > now -> blockedUntil - now
        attempt == 0 -> 0
        else -> DEFAULT_WAIT
    }

    /** Runs [block] unless a pass already holds [running] (then null): one pass at a time, a second one is dropped. */
    suspend fun <T> onePass(running: AtomicBoolean, block: suspend () -> T): T? {
        if (!running.compareAndSet(false, true)) return null
        return try {
            block()
        } finally {
            running.set(false)
        }
    }

    /** Waits out [blockedUntil] before a request; a 429 moves it to now + Retry-After. */
    val interceptor = Interceptor { chain ->
        val wait = blockedUntil - System.currentTimeMillis()
        if (wait > 0) Thread.sleep(wait)
        chain.proceed(chain.request()).also {
            if (it.code == 429) blockedUntil = System.currentTimeMillis() + retryAfterMillis(it.header("Retry-After"))
        }
    }
}
