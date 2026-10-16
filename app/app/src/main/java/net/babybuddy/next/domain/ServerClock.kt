package net.babybuddy.next.domain

/**
 * Tracks the skew between device wall time and the Baby Buddy server's clock, captured from the
 * HTTP `Date` response header on every successful response (brief 00 pattern #3; the original app
 * does the same trick — research doc §6 "clock skew"). All timestamps we SEND to the server are
 * stamped with [nowMillis] so entries never land "in the future" server-side.
 *
 * Pure JVM; the interceptor layer feeds [onServerTimeSample], a persistence hook (SessionStore)
 * receives offsets via [offsetListener].
 */
class ServerClock(private val clock: Clock) {

    @Volatile
    private var offsetMs: Long = 0

    /** Set by the DI container: persists the offset (DataStore) whenever it changes. */
    @Volatile
    var offsetListener: ((Long) -> Unit)? = null

    /** Current best estimate of "server now", in epoch millis. */
    fun nowMillis(): Long = clock.nowMillis() + offsetMs

    fun currentOffsetMillis(): Long = offsetMs

    /** Restores a persisted offset (app start). */
    fun restoreOffset(offsetMillis: Long) {
        offsetMs = offsetMillis
    }

    /**
     * Feed the server's epoch time from a response `Date` header. Returns the new offset,
     * or null when the header was absent/unparseable.
     */
    fun onServerTimeSample(serverEpochMillis: Long): Long {
        val newOffset = serverEpochMillis - clock.nowMillis()
        offsetMs = newOffset
        offsetListener?.invoke(newOffset)
        return newOffset
    }
}
