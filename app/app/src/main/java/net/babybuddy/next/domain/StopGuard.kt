package net.babybuddy.next.domain

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
     * Dialog body, e.g. "You've been tracking Sleep for 12:34." — clock style matches the
     * running panel (HH:MM:ss once an hour or more is involved, otherwise M:SS).
     */
    fun dialogBody(label: String, elapsedMillis: Long): String =
        "You've been tracking $label for ${clockText(elapsedMillis)}."

    private fun clockText(elapsedMillis: Long): String {
        val totalSeconds = elapsedMillis.coerceAtLeast(0) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) {
            String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        } else {
            String.format(Locale.US, "%d:%02d", m, s)
        }
    }
}
