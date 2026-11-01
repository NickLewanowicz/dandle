package app.dandle.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Legacy glance-state reads (P1 QA 2026-10-31): the pre-refactor build saved "child_id" with
 * longPreferencesKey, and DataStore Preferences keys compare equal by NAME alone — a direct
 * `prefs[stringPreferencesKey("child_id")]` ClassCastExceptions the stored Long at the call
 * site. Both widgets must read via the raw map so a legacy Long degrades to null (and the next
 * saveConfig write-heals the key into a String).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class WidgetLegacyStateTest {

    @Test
    fun `a legacy Long child_id reads as null instead of ClassCastException (QuickActions)`() {
        val legacy: Preferences = mutablePreferencesOf(longPreferencesKey("child_id") to 7L)
        assertNull("legacy Long must degrade to null, never throw", QuickActionsWidget.configuredChildId(legacy))
    }

    @Test
    fun `a legacy Long child_id reads as null instead of ClassCastException (CareDashboard)`() {
        val legacy: Preferences = mutablePreferencesOf(longPreferencesKey("child_id") to 7L)
        assertNull("legacy Long must degrade to null, never throw", CareDashboardWidget.configuredChildId(legacy))
    }

    @Test
    fun `a current String child_id still reads through`() {
        val current: Preferences = mutablePreferencesOf(stringPreferencesKey("child_id") to "local-child-1")
        assertEquals("local-child-1", QuickActionsWidget.configuredChildId(current))
        assertEquals("local-child-1", CareDashboardWidget.configuredChildId(current))
    }

    @Test
    fun `absent child_id reads as null`() {
        val empty: Preferences = mutablePreferencesOf()
        assertNull(QuickActionsWidget.configuredChildId(empty))
        assertNull(CareDashboardWidget.configuredChildId(empty))
    }
}
