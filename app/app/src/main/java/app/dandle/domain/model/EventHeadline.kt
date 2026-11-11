package app.dandle.domain.model

import app.dandle.domain.FeedingOptions

/**
 * Timeline headline formats — MUST be consistent app-wide (functional spec §4.1):
 * - Feeding: "Bottle · Amount: 120" (method · amount; amount omitted when null or ≤ 0 —
 *            UX-review W9). Non-breast-milk types are appended ("Bottle · Formula ·
 *            Amount: 110") — the method alone erases the type for solid food and hides
 *            formula intake (Elena, round 2).
 * - Diaper:  "Wet" / "Solid" / "Wet + Solid" (color is rendered ONCE, as the row's
 *            swatch dot + label — never repeated inside the headline)
 * - Sleep:   "Sleep"
 * - Note:    first line of the note text
 * - Pumping: "Pump · Amount: 85"
 * - Tummy:   "Tummy time" (+" · milestone")
 *
 * Amounts are UNIT-LESS ("Amount: 2", the legacy app's convention): the server stores a
 * bare float and families use different units (mL, oz) — suffixing any unit invents data
 * (owner, beta 2026-10-31: "Bottle · 2 mL" was actually 2 oz).
 */
object EventHeadline {

    fun headline(event: Event): String = when (event.type) {
        EventType.FEEDING -> {
            val method = event.feedingMethod?.replaceFirstChar { it.uppercase() } ?: "Feed"
            val type = event.feedingType
                ?.takeIf { it.isNotBlank() && it != "breast milk" }
                ?.let { " · ${FeedingOptions.typeLabel(it)}" }
                .orEmpty()
            val amount = amountText(event)
            "$method$type$amount"
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
            // Color is deliberately NOT in the headline: the row renders the swatch dot +
            // color label once (a second copy here read "Wet (Green) ● Green" — Elena/UX round 2).
            base
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
     * " · Amount: 120" segment, omitted when the amount is null or ≤ 0 (UX-review W9: a
     * failed-sync pump with amount 0 must not present "Amount: 0" as data; kept in lockstep
     * with the widget mapper's [app.dandle.widget.WidgetStateMapper.detailText]).
     */
    private fun amountText(event: Event): String =
        event.amount
            ?.takeIf { it > 0.0 }
            ?.let { " · Amount: ${trimNumber(it)}" }
            .orEmpty()

    /** 120.0 prints as "120", 2.5 as "2.5". */
    private fun trimNumber(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()
}
