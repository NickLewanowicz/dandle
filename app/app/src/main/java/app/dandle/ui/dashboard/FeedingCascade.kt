package app.dandle.ui.dashboard

import app.dandle.domain.FeedingOptions

/**
 * The feeding two-state cascade (design framework v2.3 rule 5, spec §3.1): type gates methods
 * (via [FeedingOptions.methodsFor]), and a COMPLETE choice (type + method) collapses to a
 * one-line summary so only Amount + Note remain on the card. Pure Kotlin — no Compose — so the
 * state machine is pinned by FeedingCascadeTest and shared by the running card AND the Quick
 * Edit sheet (the cascade applies wherever feeding fields render).
 *
 * Visibility matrix (draft values come from the panel/edit draft, so learned defaults start
 * the card COLLAPSED — the happy path is type+method already right):
 * - no type            → show the TYPE row only ("Type — pick one"), no methods exist yet
 * - type, no method    → TYPE row + METHOD row filtered by type
 * - both, not expanded → collapsed SUMMARY row + the remaining fields
 * - both, expanded     → TYPE row + METHOD row again (user asked to change via summary tap)
 */
data class FeedingCascade(
    val showTypeRow: Boolean,
    val showMethodRow: Boolean,
    val showSummary: Boolean,
    val summaryText: String?,
) {
    /** A complete, collapsed choice renders as "Type · Method" with a change affordance. */
    val isCollapsed: Boolean get() = showSummary

    companion object {
        fun typeLabel(wire: String?): String = FeedingOptions.typeLabel(wire)
        fun methodLabel(wire: String?): String = FeedingOptions.methodLabel(wire)
    }
}

fun feedingCascade(typeWire: String?, methodWire: String?, expanded: Boolean): FeedingCascade {
    val complete = typeWire != null && methodWire != null
    val collapsed = complete && !expanded
    return FeedingCascade(
        showTypeRow = !collapsed,
        showMethodRow = typeWire != null && !collapsed,
        showSummary = collapsed,
        summaryText = if (collapsed) {
            "${FeedingOptions.typeLabel(typeWire)} · ${FeedingOptions.methodLabel(methodWire)}"
        } else {
            null
        },
    )
}
