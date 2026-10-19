package net.babybuddy.next.domain

import net.babybuddy.next.domain.model.Event
import net.babybuddy.next.domain.model.EventType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Parent-language translation of server rejection reasons (spec §3.8, §6.2 — "never raw
 * server-speak"). The overlap rejection names the clashing entry; we name it in plain words and
 * prefer the LOCALLY LOADED clash row's times over the server's rendering of them.
 *
 * Pure Kotlin: translation is unit-testable and used by the failure dialog and the Quick Edit
 * sheet's failure banner (Stage 3).
 */
object FailureMessage {

    // Localized SHORT time format: follows the device 12/24h setting, so the failure
    // dialog speaks the same time dialect as the timeline rows (HH:mm vs "1:15 PM" was
    // two dialects in one moment — Elena, persona round 2).
    private val rangeFormat = DateTimeFormatter.ofLocalizedTime(java.time.format.FormatStyle.SHORT)
    private val clashInReason = Regex("""Conflicting entry:\s*(.+?)\s*\((.+?)\s*-\s*(.+?)\)\s*""")

    /**
     * The failure dialog body.
     * @param reason the stored (markup-stripped) server reason; may be null.
     * @param conflictUiPath conflict target's web path, e.g. "sleep" (parsed at sync time).
     * @param conflictServerId conflict target's server id, when parsed.
     * @param clash the clashing row when it is loaded locally (used for exact local times).
     */
    fun translate(
        reason: String?,
        conflictUiPath: String? = null,
        conflictServerId: Long? = null,
        clash: Event? = null,
    ): String {
        val clean = reason?.trim().takeUnless { it.isNullOrEmpty() }
        val isOverlap = clean?.contains("intersect", ignoreCase = true) == true ||
            conflictUiPath != null ||
            clash != null
        if (isOverlap) {
            val kind = overlapKind(conflictUiPath, clash, clean)
            val range = overlapRange(clash, clean)
            return when {
                kind != null && range != null -> "This overlaps the $range $kind."
                kind != null -> "This overlaps an existing $kind."
                else -> "This overlaps another entry."
            }
        }
        if (clean == null) return "The server rejected this entry. Edit it or try again."
        return translateGeneric(clean)
    }

    /** Generic single-line reasons we can phrase more kindly; falls back to the server text. */
    fun translateGeneric(reason: String): String = when {
        reason.contains("future", ignoreCase = true) ||
            reason.contains("in the past", ignoreCase = true) -> "Entries can't be in the future."
        reason.contains("deleted", ignoreCase = true) -> "This entry was deleted on the server."
        else -> reason
    }

    /** Session-expiry reassurance (spec §6.3.6): count = PENDING + FAILED entries. */
    fun sessionExpired(unsentCount: Int): String {
        val noun = if (unsentCount == 1) "entry" else "entries"
        val verb = if (unsentCount == 1) "is" else "are"
        return "Session expired — your $unsentCount unsent $noun $verb safe. " +
            "Sign in and they'll go up."
    }

    private fun plural(@Suppress("UNUSED_PARAMETER") count: Int) = "entries"

    private fun overlapKind(uiPath: String?, clash: Event?, reason: String?): String? = when {
        clash != null -> kindLabel(clash.type)
        uiPath != null -> resolveType(uiPath)?.let(::kindLabel)
            ?: uiPath.replaceFirstChar { it.uppercase() }

        else -> reason?.let { r ->
            // "Conflicting entry: Sleep (…)" — label precedes the parenthesized range.
            clashInReason.find(r)?.groupValues?.get(1)?.lowercase()?.let { label ->
                EventType.entries
                    .map { it to kindLabel(it) }
                    .firstOrNull { label.contains(it.second, ignoreCase = true) }
                    ?.second
                    ?: label.replaceFirstChar { it.uppercase() }
            }
        }
    }

    /** Conflict refs carry the WEB path ("tummy-time"); resolve against both path spellings. */
    private fun resolveType(path: String): EventType? =
        EventType.entries.firstOrNull { it.uiPath == path || it.apiPath == path }

    private fun kindLabel(type: EventType): String = when (type) {
        EventType.FEEDING -> "feed"
        EventType.CHANGE -> "diaper change"
        EventType.SLEEP -> "nap"
        EventType.NOTE -> "note"
        EventType.PUMPING -> "pump"
        EventType.TUMMY_TIME -> "tummy time"
    }

    private fun overlapRange(clash: Event?, reason: String?): String? {
        if (clash != null) {
            return "${format(clash.startMillis)}–${format(clash.endMillis)}"
        }
        if (reason != null) {
            clashInReason.find(reason)?.let { m ->
                // Times from the server may carry a date prefix ("11/19/17 03:00") — keep the
                // time-of-day only; the local row is preferred when loaded anyway.
                val start = timeTokenOf(m.groupValues[2])
                val end = timeTokenOf(m.groupValues[3])
                return "$start–$end"
            }
        }
        return null
    }

    private val timeToken = Regex("""\d{1,2}:\d{2}(?::\d{2})?(?:\s*[AP]M)?""", RegexOption.IGNORE_CASE)

    private fun timeTokenOf(segment: String): String =
        timeToken.find(segment)?.value ?: segment

    private fun format(epochMillis: Long): String =
        rangeFormat.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))
}
