package app.dandle.data.backend

/**
 * The typed backend boundary (brief 05 §1). Everything a transport can fail with, expressed
 * without HTTP/Retrofit types — repositories, the outbox drainer, and ViewModels see only this
 * taxonomy; each backend adapter maps its wire errors onto it at the edge.
 */
sealed interface BackendError {

    /** No answer at all (connection refused/lost, DNS, timeout). Offline is a normal path. */
    data object Unreachable : BackendError

    /** Credentials expired/invalid — the queue pauses and the user signs in again (spec §6.3.6). */
    data object AuthExpired : BackendError

    /**
     * The backend rejected the entry's CONTENT (400-class): permanent for this payload.
     * Carries the human-readable reason + optional conflicting-entry reference for the
     * overlap-resolution flow (spec §3.8, §6.2).
     */
    data class Validation(
        val rawBody: String?,
        val displayReason: String?,
        val conflictUiPath: String? = null,
        val conflictServerId: Long? = null,
    ) : BackendError

    /** The targeted record no longer exists server-side. */
    data object NotFound : BackendError

    /** Any other 4xx: unexpected client error, treated as terminal for the op. */
    data class Http(val status: Int, val rawBody: String?) : BackendError

    /** 5xx: the backend hiccuped; the op stays queued and retried. */
    data class ServerError(val status: Int) : BackendError
}

/**
 * Throwable carrier for gateway methods that report failures by throwing (PullGateway,
 * TimerMirror). The taxonomy itself stays a pure sealed interface; unwrap with [error].
 */
class BackendException(val error: BackendError) : Exception(error.toString())

/** Result of a single push attempt (brief 05 §1). */
sealed interface PushResult {
    /** Landed; carries the server-assigned record id (CREATE only). */
    data class Ok(val serverId: Long) : PushResult

    /** Delete landed, or the record was already gone — both leave the server without the row. */
    data object Deleted : PushResult

    data class Err(val error: BackendError) : PushResult
}
