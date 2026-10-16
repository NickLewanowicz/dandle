package net.babybuddy.next.domain.model

/**
 * Core domain models (pure Kotlin — no android.*, KMP-ready per DECISION.md §3).
 * These are what the UI layer sees; data-layer types (DTOs, Room entities) never leak up.
 */

/** The six tracked event types, with their exact server routes (research doc §3/§7). */
enum class EventType(
    /** REST list endpoint segment, e.g. "tummy-times". */
    val apiPath: String,
    /** Web-UI path segment for the long-press escape hatch, e.g. "tummy-time" (research doc §7). */
    val uiPath: String,
    /** Timed types run on timers (start/end); instant types happen at a moment (time). */
    val isTimed: Boolean,
    /**
     * Server-timer name this app uses for the type, matching the original client's
     * "<Type>-BBapp:<n>" scheme with n = position in [Feeding, Sleep, Tummy time, Pumping]
     * (research doc §4.4). Null for non-timer types.
     */
    val serverTimerName: String?,
) {
    FEEDING("feedings", "feedings", true, "Feeding-BBapp:1"),
    CHANGE("changes", "changes", false, null),
    SLEEP("sleep", "sleep", true, "Sleep-BBapp:2"),
    NOTE("notes", "notes", false, null),
    PUMPING("pumping", "pumping", true, "Pumping-BBapp:4"),
    TUMMY_TIME("tummy-times", "tummy-time", true, "Tummy time-BBapp:3");

    companion object {
        fun fromApiPath(apiPath: String): EventType? = entries.firstOrNull { it.apiPath == apiPath }
    }
}

/** Per-entry sync state (spec §6.2). SYNCED renders no badge. */
enum class SyncState { SYNCED, PENDING, FAILED }

data class Child(
    val id: Long,
    val firstName: String,
    val lastName: String?,
    val slug: String,
    val birthDate: String,
    val birthTime: String?,
)

/** A timeline event, all six types unified. Instant types keep end == start. */
data class Event(
    val localUuid: String,
    val serverId: Long?,
    val type: EventType,
    val childId: Long,
    val startMillis: Long,
    val endMillis: Long,
    // Type-specific payload (only the fields relevant to [type] are non-null).
    val feedingType: String? = null,
    val feedingMethod: String? = null,
    val amount: Double? = null,
    val wet: Boolean? = null,
    val solid: Boolean? = null,
    val color: String? = null,
    val noteText: String? = null,
    val milestone: String? = null,
    val syncState: SyncState = SyncState.SYNCED,
    /** Raw (markup-stripped) server reason, stored when FAILED (spec §6.2). */
    val failureReason: String? = null,
    /** Parsed conflicting entry from an overlap rejection (spec §3.8). */
    val conflictUiPath: String? = null,
    val conflictServerId: Long? = null,
    val createdAtMillis: Long = 0,
    val updatedAtMillis: Long = 0,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0)
}

/** Field bundle for creating or editing an event (the Quick Edit sheet's write model, Stage 2). */
data class EventDraft(
    val type: EventType,
    val childId: Long,
    val startMillis: Long,
    val endMillis: Long,
    val feedingType: String? = null,
    val feedingMethod: String? = null,
    val amount: Double? = null,
    val wet: Boolean? = null,
    val solid: Boolean? = null,
    val color: String? = null,
    val noteText: String? = null,
    val milestone: String? = null,
)

/** A running timer (spec §2 active-timers strip / §3 quick actions). */
data class RunningTimer(
    val localId: Long,
    val kind: EventType,
    val childId: Long,
    val startedAtMillis: Long,
    /** Server timer created when the start happened online; null when started offline. */
    val serverTimerId: Long?,
)
