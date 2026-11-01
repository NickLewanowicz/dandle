package app.dandle.data.backend

import app.dandle.domain.model.EventType

/** A server-side timer as the mirror sees it (Baby Buddy web-UI visibility feature, spec §3). */
data class ServerTimer(
    val id: Long,
    val name: String?,
    val childId: Long?,
    val startIso: String?,
)

/**
 * Optional capability (brief 05 §1): mirroring running timers to the backend so they show in a
 * web UI. Baby Buddy implements it (the "{Type}-BBapp:<n>" convention); on-device and cloud
 * backends are `null` — timers then live purely locally. Implementations throw [BackendError].
 */
interface TimerMirror {
    suspend fun create(childId: Long, kind: EventType, startIso: String): Long
    suspend fun delete(serverTimerId: Long)
    suspend fun listForChild(childId: Long): List<ServerTimer>
}
