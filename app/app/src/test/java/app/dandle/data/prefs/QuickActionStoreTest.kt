package app.dandle.data.prefs

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Learned feeding defaults + panel drafts (spec §3.1, UX spec §3 draft persistence). */
class QuickActionStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun newStore(): QuickActionStore =
        QuickActionStore(tmp.newFile("qa_${System.nanoTime()}.preferences_pb").absolutePath.toPath())

    @Test
    fun `feeding defaults fall back to breast milk plus bottle`() = runTest {
        val store = newStore()
        assertEquals(FeedingDefault.FALLBACK, store.feedingDefault(childId = 1))
    }

    @Test
    fun `remembered defaults are per child`() = runTest {
        val store = newStore()
        store.rememberFeeding(childId = 1, typeWire = "formula", methodWire = "bottle")
        store.rememberFeeding(childId = 2, typeWire = "breast milk", methodWire = "left breast")

        assertEquals(FeedingDefault("formula", "bottle"), store.feedingDefault(1))
        assertEquals(FeedingDefault("breast milk", "left breast"), store.feedingDefault(2))
        // Child 3 never saved a feeding: still the fallback.
        assertEquals(FeedingDefault.FALLBACK, store.feedingDefault(3))
    }

    @Test
    fun `remembered defaults overwrite earlier values`() = runTest {
        val file = tmp.newFile("qa_restart.preferences_pb")
        val store = QuickActionStore(file.absolutePath.toPath())
        store.rememberFeeding(childId = 5, typeWire = "formula", methodWire = "bottle")
        store.rememberFeeding(childId = 5, typeWire = "solid food", methodWire = "self fed")

        assertEquals(FeedingDefault("solid food", "self fed"), store.feedingDefault(5))
        // The write went through the DataStore's persisted file (same store instance re-reads).
        assertEquals(
            FeedingDefault("solid food", "self fed"),
            store.observeFeedingDefault(5).first(),
        )
    }

    @Test
    fun `panel drafts persist and reopen`() = runTest {
        val store = newStore()
        store.savePanel(
            childId = 1,
            type = "SLEEP",
            draft = PanelDraft(open = true, noteText = "midnight nap"),
        )
        val draft = store.panel(1, "SLEEP")!!
        assertTrue(draft.open)
        assertEquals("midnight nap", draft.noteText)
        // Per type + child: untouched panels read null.
        assertNull(store.panel(1, "FEEDING"))
        assertNull(store.panel(2, "SLEEP"))
    }

    @Test
    fun `panel drafts keep the started-minutes-ago adjust (T2)`() = runTest {
        val store = newStore()
        store.savePanel(1, "SLEEP", PanelDraft(open = true, startedMinutesAgo = 20))
        assertEquals(20, store.panel(1, "SLEEP")!!.startedMinutesAgo)
        // Drafts stored before the field existed (no key) read the default, not garbage.
        assertEquals(0, store.panel(1, "FEEDING", )?.startedMinutesAgo ?: 0)
        store.savePanel(1, "SLEEP", PanelDraft(open = true))
        assertEquals(0, store.panel(1, "SLEEP")!!.startedMinutesAgo)
    }

    @Test
    fun `successful save clears the draft`() = runTest {
        val store = newStore()
        store.savePanel(1, "FEEDING", PanelDraft(open = true, amountText = "120"))
        store.clearPanel(1, "FEEDING")
        assertNull(store.panel(1, "FEEDING"))
    }

    @Test
    fun `corrupt draft json starts clean instead of crashing`() = runTest {
        val store = newStore()
        assertNull(store.decodeDraft("not json at all {"))
        // "{}" is valid: decodes to the all-defaults draft.
        assertEquals(PanelDraft(), store.decodeDraft("""{}"""))
        assertEquals("ok", store.decodeDraft("""{"text":"ok"}""")?.text)
        // Unknown members are ignored (decode-side ignoreUnknownKeys).
        assertEquals("kept", store.decodeDraft("""{"text":"kept","future":"field"}""")?.text)
    }

    @Test
    fun `long-press hint shows exactly once`() = runTest {
        val store = newStore()
        assertFalse(store.wasLongPressHintShown())
        store.setLongPressHintShown()
        assertTrue(store.wasLongPressHintShown())
    }

    @Test
    fun `sign out clears drafts defaults and hints`() = runTest {
        val store = newStore()
        store.rememberFeeding(1, "formula", "bottle")
        store.savePanel(1, "NOTE", PanelDraft(text = "secret"))
        store.setLongPressHintShown()

        store.clearAll()

        assertEquals(FeedingDefault.FALLBACK, store.feedingDefault(1))
        assertNull(store.panel(1, "NOTE"))
        assertFalse(store.wasLongPressHintShown())
        assertEquals(null, store.observePanel(1, "NOTE").first())
    }
}
