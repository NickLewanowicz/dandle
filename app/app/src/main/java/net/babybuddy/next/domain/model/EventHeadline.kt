package net.babybuddy.next.domain.model

/**
 * Timeline headline formats — MUST be consistent app-wide (functional spec §4.1):
 * - Feeding: "Bottle · 120 mL" (method · amount; amount omitted when null)
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
            val amount = event.amount?.let { " · ${trimNumber(it)} mL" }.orEmpty()
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
            val amount = event.amount?.let { " · ${trimNumber(it)} mL" }.orEmpty()
            "Pump$amount"
        }
        EventType.TUMMY_TIME -> {
            val milestone = event.milestone?.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty()
            "Tummy time$milestone"
        }
    }

    /** 120.0 prints as "120", 2.5 as "2.5". */
    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
