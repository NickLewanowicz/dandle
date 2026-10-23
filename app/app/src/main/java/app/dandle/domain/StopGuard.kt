package app.dandle.domain

import java.util.Locale

/**
 * The stop guard for running timers (functional spec §3 common rules — exact original
 * semantics, emphasis per spec v0.2):
 * - elapsed ≤ 10s → discard silently (a mis-tap; a dialog would be the real friction).
 * - elapsed > 10s → confirm "Stop timer?" with **Save** the emphasized (filled) action;
 *   Keep and Discard are subdued text buttons.
 */
object StopGuard {

    const val THRESHOLD_MILLIS: Long = 10_000L

    /** True when stopping [elapsedMillis] of tracking needs the confirmation dialog. */
    fun shouldConfirm(elapsedMillis: Long): Boolean = elapsedMillis > THRESHOLD_MILLIS

    /** Dialog title (fixed copy). */
    const val DIALOG_TITLE = "Stop timer?"

    /**
     * Dialog body, e.g. "You've been tracking Sleep for 16 sec." Durations are SPELLED
     * ("4 min 12 sec") — the old clock style ("0:16") was unreadable as minutes-vs-seconds
     * at 3am (Jordan, persona round 2).
     */
    fun dialogBody(label: String, elapsedMillis: Long): String =
        "You've been tracking $label for ${spokenText(elapsedMillis)}."

    private fun spokenText(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis.coerceAtLeast(0) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return when {
            h > 0 -> "${h} h ${m} min ${s} sec"
            m > 0 -> "${m} min ${s} sec"
            else -> "${s} sec"
        }
    }
}
