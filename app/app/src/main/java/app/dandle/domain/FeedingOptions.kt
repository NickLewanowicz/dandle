package app.dandle.domain

/**
 * Feeding type → method cascade (functional spec §3.1). Wire values are the EXACT server enums
 * (research doc §4); method matrices verified against the existing app v2.6.4
 * (docs/flows/existing-app/04-feeding.md):
 * - breast milk → left breast, right breast, both breasts, bottle, parent fed, self fed
 * - formula / fortified breast milk / solid food → bottle, parent fed, self fed
 * (solid food DOES include bottle — corrected in QA deep-dive commit 879c1fe).
 */
object FeedingOptions {

    /** A server enum value with its display label. */
    data class Option(val wire: String, val label: String)

    val TYPES = listOf(
        Option("breast milk", "Breast milk"),
        Option("formula", "Formula"),
        // Display is shortened (UX spec §3); the wire value stays the exact server enum.
        Option("fortified breast milk", "Fortified milk"),
        Option("solid food", "Solids"),
    )

    val NURSING_METHODS = listOf(
        Option("left breast", "Left breast"),
        Option("right breast", "Right breast"),
        Option("both breasts", "Both breasts"),
        Option("bottle", "Bottle"),
        Option("parent fed", "Parent fed"),
        Option("self fed", "Self fed"),
    )

    /** Non-nursing trio — offered for every type except breast milk. */
    val FEEDING_METHODS = listOf(
        NURSING_METHODS[3],
        NURSING_METHODS[4],
        NURSING_METHODS[5],
    )

    val ALL_METHODS: List<Option> = NURSING_METHODS

    /** First-use fallback (spec §3.1): breast milk + bottle. */
    const val DEFAULT_TYPE = "breast milk"
    const val DEFAULT_METHOD = "bottle"

    /** Methods offered for [typeWire]; unknown/blank types fall back to the non-nursing trio. */
    fun methodsFor(typeWire: String?): List<Option> =
        if (typeWire == DEFAULT_TYPE) NURSING_METHODS else FEEDING_METHODS

    /**
     * Cascade rule (UX spec §3): after a type change, a method that is no longer offered
     * resets to Bottle; a still-valid method is kept.
     */
    fun resetMethod(newTypeWire: String?, currentMethodWire: String?): String =
        if (currentMethodWire != null && methodsFor(newTypeWire).any { it.wire == currentMethodWire }) {
            currentMethodWire
        } else {
            DEFAULT_METHOD
        }

    fun typeLabel(wire: String?): String =
        TYPES.firstOrNull { it.wire == wire }?.label
            ?: wire?.replaceFirstChar { it.uppercase() }
            ?: "Breast milk"

    fun methodLabel(wire: String?): String =
        ALL_METHODS.firstOrNull { it.wire == wire }?.label
            ?: wire?.replaceFirstChar { it.uppercase() }
            ?: "Bottle"
}
