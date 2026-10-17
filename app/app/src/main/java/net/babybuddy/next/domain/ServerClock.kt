package net.babybuddy.next.domain

import kotlin.math.abs

/**
 * Tracks the skew between device wall time and the Baby Buddy server's clock, captured from the
 * HTTP `Date` response header on every successful response (brief 00 pattern #3; the original app
 * does the same trick — research doc §6 "clock skew"). All timestamps we SEND to the server are
 * stamped with [nowMillis] so entries never land "in the future" server-side.
 *
 * Persistence is THROTTLED (review §a1): the in-memory mirror updates on every sample, but the
 * persistence hook ([offsetListener]) fires only when the offset moved more than
 * [PERSIST_THRESHOLD_MS] since the last persisted value. Every response used to trigger a
 * DataStore write → a sessionFlow emission → a RootNavigation recomposition — the storm that
 * turned a mid-login write into the login dead-end and would have re-rendered other screens too.
 *
 * Pure JVM; the interceptor layer feeds [onServerTimeSample], a persistence hook (SessionStore)
 * receives offsets via [offsetListener].
 */
class ServerClock(private val clock: Clock) {

    @Volatile
    private var offsetMs: Long = 0

    /** Last offset handed to [offsetListener] — the throttle's reference point. */
    @Volatile
    private var lastPersistedOffsetMs: Long = 0

    /** Set by the DI container: persists the offset (DataStore) whenever it changes. */
    @Volatile
    var offsetListener: ((Long) -> Unit)? = null

    /** Current best estimate of "server now", in epoch millis. */
    fun nowMillis(): Long = clock.nowMillis() + offsetMs

    fun currentOffsetMillis(): Long = offsetMs

    /** Restores a persisted offset (app start). */
    fun restoreOffset(offsetMillis: Long) {
        offsetMs = offsetMillis
        lastPersistedOffsetMs = offsetMillis
    }

    /**
     * Feed the server's epoch time from a response `Date` header. Returns the new offset (always
     * applied in memory); [offsetListener] fires only when the offset moved more than
     * [PERSIST_THRESHOLD_MS] since the last persisted value. Null when the header was
     * absent/unparseable (caller handles).
     */
    fun onServerTimeSample(serverEpochMillis: Long): Long {
        val newOffset = serverEpochMillis - clock.nowMillis()
        offsetMs = newOffset
        if (abs(newOffset - lastPersistedOffsetMs) > PERSIST_THRESHOLD_MS) {
            lastPersistedOffsetMs = newOffset
            offsetListener?.invoke(newOffset)
        }
        return newOffset
    }

    companion object {
        /** Persist only meaningful clock drift (30s); sub-threshold drift stays in memory. */
        const val PERSIST_THRESHOLD_MS: Long = 30_000L
    }
}
