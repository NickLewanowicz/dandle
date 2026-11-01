package app.dandle.ui.dashboard

import app.dandle.data.prefs.PanelDraft
import app.dandle.domain.model.EventType
import app.dandle.domain.model.RunningTimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pins the STARTED row on the running-timer card (persona round 2: absolute time first).
 * Regression (QA 2026-10-19): an ADOPTED timer — app relaunched while the timer ran —
 * rendered "Started <now> · just now" for a hours-old timer because the row read the
 * draft's relative minutesAgo (0 after restore) instead of the timer's absolute start.
 */
class StartedLabelTest {

    private val zone: ZoneId = ZoneId.systemDefault()
    private val hhmm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    private fun clockOf(epochMillis: Long): String =
        hhmm.format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    private fun panel(
        startedMinutesAgo: Int = 0,
        timerStart: Long? = null,
    ): OpenPanel = OpenPanel(
        type = EventType.SLEEP,
        draft = PanelDraft(startedMinutesAgo = startedMinutesAgo),
        timer = timerStart?.let {
            RunningTimer(localId = 1, kind = EventType.SLEEP, childId = "1", startedAtMillis = it, serverTimerId = null)
        },
    )

    @Test
    fun `adopted timer shows its real age, not just now`() {
        val now = 1_760_000_000_000L
        val start = now - 50 * 60_000L
        val label = startedLabel(panel(timerStart = start), now)
        assertEquals("Started ${clockOf(start)} · 50m ago", label)
    }

    @Test
    fun `adopted timer rounds up to the minute`() {
        val now = 1_760_000_000_000L
        val label = startedLabel(panel(timerStart = now - 65_000L), now)
        assertTrue(label, label.endsWith("· 1m ago"))
    }

    @Test
    fun `freshly started timer still says just now`() {
        val now = 1_760_000_000_000L
        val label = startedLabel(panel(timerStart = now - 12_000L), now)
        assertEquals("Started ${clockOf(now)} · just now", label)
    }

    @Test
    fun `no timer keeps the draft-relative behavior`() {
        val now = 1_760_000_000_000L
        val label = startedLabel(panel(startedMinutesAgo = 30), now)
        assertEquals("Started ${clockOf(now - 30 * 60_000L)} · 30m ago", label)
    }

    @Test
    fun `adjust backdate adds on top of an adopted timer like the save path`() {
        // Save: start = timerStart − adjust (TimerRepository.stopAndSave). The row must
        // show the same implied start: age + adjust back from now.
        val now = 1_760_000_000_000L
        val start = now - 50 * 60_000L
        val label = startedLabel(panel(startedMinutesAgo = 30, timerStart = start), now)
        assertEquals("Started ${clockOf(start - 30 * 60_000L)} · 1h 20m ago", label)
    }
}
