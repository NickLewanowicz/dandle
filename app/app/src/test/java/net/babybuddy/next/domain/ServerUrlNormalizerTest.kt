package net.babybuddy.next.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** URL normalization rules (spec §1.1.2/§1.1.3). */
class ServerUrlNormalizerTest {

    @Test
    fun `bare host gets https`() {
        val n = normalizeServerUrl("baby.example.com")
        assertEquals("https://baby.example.com", n.url)
        assertFalse(n.isCleartext)
    }

    @Test
    fun `trailing slash trimmed`() {
        assertEquals("https://baby.example.com", normalizeServerUrl("baby.example.com/").url)
        assertEquals("http://a.b", normalizeServerUrl("http://a.b///").url)
    }

    @Test
    fun `explicit https keeps scheme`() {
        assertEquals("https://x.y", normalizeServerUrl("https://x.y").url)
    }

    @Test
    fun `http flagged as cleartext`() {
        val n = normalizeServerUrl("http://10.0.2.2:8000/")
        assertEquals("http://10.0.2.2:8000", n.url)
        assertTrue(n.isCleartext)
    }

    @Test
    fun `whitespace trimmed`() {
        assertEquals("https://x.y", normalizeServerUrl("  x.y  ").url)
    }

    @Test
    fun `host with path keeps path`() {
        assertEquals("https://x.y/bb", normalizeServerUrl("https://x.y/bb").url)
    }
}

class ElapsedTimeFormatterTest {

    @Test
    fun `timer counter formats HH-MM-ss`() {
        assertEquals("00:00:00", ElapsedTimeFormatter.timerCounter(0))
        assertEquals("00:00:10", ElapsedTimeFormatter.timerCounter(10_000))
        assertEquals("00:47:12", ElapsedTimeFormatter.timerCounter((47 * 60 + 12) * 1000L))
        assertEquals("01:00:00", ElapsedTimeFormatter.timerCounter(3_600_000))
        assertEquals("27:30:05", ElapsedTimeFormatter.timerCounter((27 * 3600 + 30 * 60 + 5) * 1000L))
    }

    @Test
    fun `timer counter clamps negative elapsed (clock skew safety)`() {
        assertEquals("00:00:00", ElapsedTimeFormatter.timerCounter(-1_000))
    }

    @Test
    fun `duration short per UX spec`() {
        assertEquals("0s", ElapsedTimeFormatter.durationShort(0))
        assertEquals("45s", ElapsedTimeFormatter.durationShort(45_000))
        assertEquals("59s", ElapsedTimeFormatter.durationShort(59_999))
        assertEquals("12m", ElapsedTimeFormatter.durationShort(12 * 60 * 1000L))
        assertEquals("59m", ElapsedTimeFormatter.durationShort(59 * 60_000L + 59_999))
        assertEquals("1h 05m", ElapsedTimeFormatter.durationShort((3600 + 5 * 60) * 1000L))
        assertEquals("2h 00m", ElapsedTimeFormatter.durationShort(7_200_000))
        assertEquals("3h 30m", ElapsedTimeFormatter.durationShort((3 * 3600 + 30 * 60) * 1000L))
    }
}
