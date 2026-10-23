package app.dandle.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** DRF 400-body parsing incl. the overlap message with the embedded HTML link (research doc §5.1). */
class ConflictPolicyTest {

    @Test
    fun `parses field errors`() {
        val parsed = ConflictPolicy.parse400Body("""{"end": ["Date/time can not be in the future."]}""")
        assertEquals(listOf("Date/time can not be in the future."), parsed!!.fieldErrors["end"])
        assertEquals(0, parsed.nonFieldErrors.size)
        assertNull(parsed.conflictingEntry)
        assertEquals("end: Date/time can not be in the future.", parsed.displayReason)
    }

    @Test
    fun `parses non_field_errors`() {
        val parsed = ConflictPolicy.parse400Body("""{"non_field_errors": ["Duration too long."]}""")
        assertEquals(listOf("Duration too long."), parsed!!.nonFieldErrors)
        assertNull(parsed.conflictingEntry)
    }

    @Test
    fun `parses the overlap message and extracts the conflicting entry id from the link`() {
        // Exact shape from research doc §5.1 (mark_safe'd link to the conflicting entry's page).
        val parsed = ConflictPolicy.parse400Body(
            """{"non_field_errors": ["Another entry intersects the specified time period. Conflicting entry: <a href=\"/sleep/4/\">Sleep (11/19/17 03:00 - 11/19/17 04:30)</a>"]}"""
        )
        assertEquals(ConflictRef(uiPath = "sleep", serverId = 4), parsed!!.conflictingEntry)
        assertEquals(
            "Another entry intersects the specified time period. Conflicting entry: Sleep (11/19/17 03:00 - 11/19/17 04:30)",
            parsed.displayReason,
        )
    }

    @Test
    fun `parses detail-string error bodies`() {
        val parsed = ConflictPolicy.parse400Body("""{"detail": "JSON parse error - expecting value"}""")
        assertEquals(listOf("JSON parse error - expecting value"), parsed!!.nonFieldErrors)
    }

    @Test
    fun `malformed bodies parse to null`() {
        assertNull(ConflictPolicy.parse400Body(null))
        assertNull(ConflictPolicy.parse400Body(""))
        assertNull(ConflictPolicy.parse400Body("<html>Service Unavailable</html>"))
        assertNull(ConflictPolicy.parse400Body("not json"))
    }

    @Test
    fun `markup is stripped for display`() {
        assertEquals(
            "Sleep (11/19/17 03:00 - 11/19/17 04:30)",
            ConflictPolicy.stripMarkup("<a href=\"/sleep/4/\">Sleep (11/19/17 03:00 - 11/19/17 04:30)</a>"),
        )
    }
}
