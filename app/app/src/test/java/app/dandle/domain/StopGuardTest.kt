package app.dandle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The >10s stop-guard thresholds (spec §3 common rules, exact original semantics). */
class StopGuardTest {

    @Test
    fun `at exactly 10 seconds the timer discards silently`() {
        assertFalse(StopGuard.shouldConfirm(10_000))
    }

    @Test
    fun `under 10 seconds discards silently`() {
        assertFalse(StopGuard.shouldConfirm(0))
        assertFalse(StopGuard.shouldConfirm(1))
        assertFalse(StopGuard.shouldConfirm(9_999))
    }

    @Test
    fun `over 10 seconds asks for confirmation`() {
        assertTrue(StopGuard.shouldConfirm(10_001))
        assertTrue(StopGuard.shouldConfirm(12 * 60 * 60 * 1000L))
    }

    @Test
    fun `dialog body spells the duration (no bare mm-ss clock)`() {
        assertEquals(
            "You've been tracking Sleep for 12 min 34 sec.",
            StopGuard.dialogBody("Sleep", (12 * 60 + 34) * 1000L),
        )
    }

    @Test
    fun `dialog body includes hours when one hour or more`() {
        assertEquals(
            "You've been tracking Feeding for 1 h 2 min 3 sec.",
            StopGuard.dialogBody("Feeding", (3600 + 2 * 60 + 3) * 1000L),
        )
    }

    @Test
    fun `negative elapsed is clamped`() {
        assertEquals(
            "You've been tracking Pumping for 0 sec.",
            StopGuard.dialogBody("Pumping", -5_000),
        )
    }
}
