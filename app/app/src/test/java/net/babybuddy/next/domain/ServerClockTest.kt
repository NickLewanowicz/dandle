package net.babybuddy.next.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Server-clock sampling: the in-memory offset updates on EVERY sample, but persistence
 * ([offsetListener]) is throttled to |delta| > 30s (review §a1) — one DataStore write per
 * response caused a recomposition storm (the login dead-end's amplifier).
 */
class ServerClockTest {

    private val localNow = 1_760_000_000_000L
    private val clock = Clock { localNow }
    private val serverClock = ServerClock(clock)

    private val persisted = AtomicReference<Long?>(null)
    private val persistCount = AtomicInteger(0)

    private fun sample(serverSkewMs: Long): Long =
        serverClock.onServerTimeSample(localNow + serverSkewMs)

    private fun listen() {
        serverClock.offsetListener = { offset ->
            persistCount.incrementAndGet()
            persisted.set(offset)
        }
    }

    @Test
    fun `every sample updates the in-memory offset`() {
        assertEquals(5_000, sample(5_000))
        assertEquals(5_000, serverClock.currentOffsetMillis())
        assertEquals(-2_000, sample(-2_000))
        assertEquals(-2_000, serverClock.currentOffsetMillis())
    }

    @Test
    fun `small drift below the threshold is never persisted`() {
        listen()
        sample(10_000)
        sample(-10_000)
        sample(29_000)
        assertEquals(0, persistCount.get())
    }

    @Test
    fun `drift past the threshold persists exactly once`() {
        listen()
        sample(31_000)
        assertEquals(1, persistCount.get())
        assertEquals(31_000L, persisted.get())
        // Further samples near the persisted value stay quiet.
        sample(32_000)
        sample(30_500)
        assertEquals(1, persistCount.get())
    }

    @Test
    fun `drift in the other direction persists too`() {
        listen()
        sample(-45_000)
        assertEquals(1, persistCount.get())
        assertEquals(-45_000L, persisted.get())
    }

    @Test
    fun `accumulating small drifts persist once the total moves past the threshold`() {
        listen()
        sample(15_000) // within 30s of the 0 default: quiet
        assertEquals(0, persistCount.get())
        sample(35_000) // 35s from the last persisted (0): persist
        assertEquals(1, persistCount.get())
        sample(50_000) // 15s from persisted 35s: quiet
        sample(70_000) // 35s from persisted 35s: persist
        assertEquals(2, persistCount.get())
    }

    @Test
    fun `a restored offset is the throttle reference (no immediate re-persist)`() {
        serverClock.restoreOffset(90_000)
        listen()
        sample(95_000) // 5s from the restored 90s: quiet
        assertEquals(0, persistCount.get())
    }

    @Test
    fun `without a listener nothing persists and sampling stays safe`() {
        assertNull(serverClock.offsetListener)
        assertEquals(20_000, sample(20_000))
    }
}
