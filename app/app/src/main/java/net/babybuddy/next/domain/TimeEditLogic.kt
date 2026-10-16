package net.babybuddy.next.domain

/**
 * Quick Edit sheet time math + inline validation (spec §5, UX spec §4):
 * - Moving the START of a timed entry shifts END by the same delta (duration preserved) —
 *   moving a start never breaks validation or forces a second clock fix.
 * - Changing END adjusts duration independently.
 * - Inline reasons with Save disabled: end must be after start; no future times; timed entries
 *   ≤ 24h (server rules — research doc §4: start not in future, end > start; the app is
 *   deliberately stricter and keeps end in the past too).
 */
object TimeEditLogic {

    const val MAX_DURATION_MILLIS: Long = 24 * 60 * 60_000L

    /** Stepper increments (spec: −15m / −1m / +1m / +15m). */
    const val STEP_SMALL_MINUTES: Long = 1L
    const val STEP_LARGE_MINUTES: Long = 15L

    /**
     * Start move with duration preservation. Returns the corrected (start, end) pair:
     * end shifts by the same delta as the start.
     */
    fun moveStart(startMillis: Long, endMillis: Long, newStartMillis: Long): Pair<Long, Long> {
        val delta = newStartMillis - startMillis
        return newStartMillis to endMillis + delta
    }

    /** Applies a stepper step in [minutes] (negative = earlier) to [millis]. */
    fun step(millis: Long, minutes: Long): Long = millis + minutes * 60_000L

    /**
     * The first failing constraint, as user-facing copy; null = valid (Save enabled).
     * End moves are NOT clamped here on purpose: end ≤ start must surface the inline reason
     * ("End time must be after start time.") and disable Save (spec §5).
     * [isTimed] instant types (diaper/note) only carry a time.
     */
    fun validate(
        startMillis: Long,
        endMillis: Long,
        isTimed: Boolean,
        nowMillis: Long,
    ): String? = when {
        isTimed && endMillis <= startMillis -> "End time must be after start time."
        startMillis > nowMillis || (isTimed && endMillis > nowMillis) ->
            "Entries can't be in the future."
        isTimed && endMillis - startMillis > MAX_DURATION_MILLIS ->
            "Timed entries can't be longer than 24 hours."
        else -> null
    }

    /**
     * Type-field gate mirroring the create panel (spec §3.2): a diaper needs Wet or Solid.
     * Callers combine this with [validate]; first non-null reason disables Save.
     */
    fun validateTypeFields(type: net.babybuddy.next.domain.model.EventType, wet: Boolean?, solid: Boolean?): String? =
        if (type == net.babybuddy.next.domain.model.EventType.CHANGE && wet != true && solid != true) {
            "Turn on Wet or Solid first."
        } else {
            null
        }
}
