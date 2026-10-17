package net.babybuddy.next.domain.model

/**
 * Timeline headline formats — MUST be consistent app-wide (functional spec §4.1):
 * - Feeding: "Bottle · 120 mL" (method · amount; amount omitted when null or ≤ 0 — UX-review W9)
 * - Diaper:  "Wet" / "Solid" / "Wet + Solid" (+ color name)
 * - Sleep:   "Sleep"
 * - Note:    first line of the note text
 * - Pumping: "Pump · 85 mL"
 * - Tummy:   "Tummy time" (+" · milestone")
 */
object EventHeadline {

    fun headline(event: Event): String = when (event.type) {
        EventType.FEEDING -> {
            val method = event.feedingMethod?.replaceFirstChar { it.uppercase() } ?: "Feed"
            val amount = amountText(event)
            "$method$amount"
        }
        EventType.CHANGE -> {
            val wet = event.wet == true
            val solid = event.solid == true
            val base = when {
                wet && solid -> "Wet + Solid"
                wet -> "Wet"
                solid -> "Solid"
                else -> "Diaper change"
            }
            val color = event.color?.takeIf { it.isNotBlank() }
                ?.let { " (${it.replaceFirstChar { c -> c.uppercase() }})" }
                .orEmpty()
            base + color
        }
        EventType.SLEEP -> "Sleep"
        EventType.NOTE -> event.noteText?.lineSequence()?.firstOrNull().orEmpty().ifBlank { "Note" }
        EventType.PUMPING -> {
            val amount = amountText(event)
            "Pump$amount"
        }
        EventType.TUMMY_TIME -> {
            val milestone = event.milestone?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "Tummy time$milestone"
        }
    }

    /**
     * " · 120 mL" segment, omitted when the amount is null or ≤ 0 (UX-review W9: a failed-sync
     * pump with amount 0 must not present "0 mL" as data; kept in lockstep with the widget
     * mapper's [WidgetStateMapper.detailText]).
     */
    private fun amountText(event: Event): String =
        event.amount
            ?.takeIf { it > 0.0 }
            ?.let { " · ${trimNumber(it)} mL" }
            .orEmpty()

    /** 120.0 prints as "120", 2.5 as "2.5". */
    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
