package app.dandle.domain

import java.time.Instant

/**
 * Injectable wall clock (KMP-safe: no android.* here). Everything that stamps "now" —
 * entries, timers, outbox rows — goes through this so JVM tests are deterministic.
 */
fun interface Clock {
    fun nowMillis(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }

        /** Test helper: a clock frozen at [epochMillis]. */
        fun fixed(epochMillis: Long) = Clock { epochMillis }
    }
}

fun Long.asInstant(): Instant = Instant.ofEpochMilli(this)
