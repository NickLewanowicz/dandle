package app.dandle.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsing of DRF HTTP-400 bodies and conflict metadata (research doc §5).
 *
 * Server shape: `{"field": ["msg"], "non_field_errors": ["msg", ...]}` — plus, for the four
 * overlap-checked models, a `non_field_errors` message containing an HTML link to the
 * conflicting entry's web edit page, e.g.
 *   "Another entry intersects the specified time period. Conflicting entry: <a href=\"/sleep/4/\">Sleep (11/19/17 03:00 - 11/19/17 04:30)</a>"
 * The parsed conflict id lets the UI deep-link / name the clashing entry (spec §3.8, §6.2).
 *
 * Pure Kotlin (KMP-ready). Stage 2 owns translating these into parent language; for now the
 * raw (markup-stripped) reason is stored alongside the parsed conflict reference.
 */
data class ConflictRef(
    /** Web-UI path segment of the conflicting entry, e.g. "sleep" (research doc §7). */
    val uiPath: String,
    /** Server id of the conflicting entry (backend-native id as a string). */
    val serverId: String,
)

data class DrfError(
    val fieldErrors: Map<String, List<String>>,
    val nonFieldErrors: List<String>,
    val conflictingEntry: ConflictRef?,
) {
    /** All messages joined, markup stripped. Stored as the entry's failureReason. */
    val displayReason: String
        get() = (nonFieldErrors + fieldErrors.flatMap { (field, msgs) ->
            msgs.map { "$field: $it" }
        }).joinToString("; ") { ConflictPolicy.stripMarkup(it) }
}

object ConflictPolicy {

    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a DRF 400 body; null when the body isn't a recognizable error object. */
    fun parse400Body(body: String?): DrfError? {
        if (body.isNullOrBlank()) return null
        val element = try {
            json.parseToJsonElement(body)
        } catch (_: Exception) {
            return null
        }
        val obj = element as? kotlinx.serialization.json.JsonObject ?: return null

        val fieldErrors = mutableMapOf<String, List<String>>()
        val nonFieldErrors = mutableListOf<String>()
        var detailError: String? = null

        for ((key, value) in obj) {
            val messages = value.asListOfStrings()
            when {
                key == "non_field_errors" -> nonFieldErrors += messages
                key == "detail" -> detailError = messages.firstOrNull()
                else -> fieldErrors[key] = messages
            }
        }
        detailError?.let(nonFieldErrors::add)

        val conflict = nonFieldErrors.firstNotNullOfOrNull { parseConflictLink(it) }
        return DrfError(
            fieldErrors = fieldErrors,
            nonFieldErrors = nonFieldErrors,
            conflictingEntry = conflict,
        )
    }

    /**
     * Extracts the conflicting entry's (uiPath, serverId) from the overlap message's embedded
     * HTML link: `<a href="/sleep/4/">` → ConflictRef("sleep", 4). Returns null if absent.
     */
    fun parseConflictLink(message: String): ConflictRef? {
        val href = Regex("""href="([^"]+)"""").find(message)?.groupValues?.get(1) ?: return null
        val match = Regex("""/([a-z-]+)/(\d+)/?$""").find(href) ?: return null
        return ConflictRef(uiPath = match.groupValues[1], serverId = match.groupValues[2])
    }

    /** Removes HTML tags (the overlap message embeds an <a> element). */
    fun stripMarkup(message: String): String =
        message.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), " ").trim()

    private fun JsonElement.asListOfStrings(): List<String> = when (this) {
        is JsonArray -> mapNotNull { (it as? JsonPrimitive)?.content }
        is JsonPrimitive -> listOf(content)
        else -> emptyList()
    }
}
