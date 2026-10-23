package app.dandle.domain

/**
 * Normalizes the user-typed server URL (spec §1.1.2):
 * - bare host gets `https://` prepended,
 * - trailing `/` trimmed,
 * - cleartext (`http://`) flagged so the UI can raise the warning dialog (spec §1.1.3).
 */
data class NormalizedServerUrl(val url: String, val isCleartext: Boolean)

fun normalizeServerUrl(input: String): NormalizedServerUrl {
    var t = input.trim()
    if (!t.startsWith("http://", ignoreCase = true) && !t.startsWith("https://", ignoreCase = true)) {
        t = "https://$t"
    }
    t = t.trimEnd('/')
    return NormalizedServerUrl(url = t, isCleartext = t.startsWith("http://", ignoreCase = true))
}
