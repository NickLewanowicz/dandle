package app.dandle.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema decisions (brief 01 §5 asked for a justified choice; brief 05 §3 changed identity):
 *
 * EventEntity is a SINGLE table with a `type` discriminator + nullable per-type columns.
 * Rationale: the app's core query is the merged six-type timeline ("all events for child X,
 * newest first, day-grouped") plus a 4-minute duplicate guard across same-type entries
 * (spec §2.4, §3.7). A single table makes both a plain indexed ORDER BY (no 6-way UNION,
 * no per-type merge in memory), and keeps offline-first invariants (syncState/outbox
 * bookkeeping) uniform for every type. Per-type columns total ~9 small nullables — far
 * cheaper than six tables + a view. Revisit only if a type grows heavy fields (images).
 *
 * All timestamps are epoch millis (Long) per the brief; type values reference
 * domain.model.EventType / SyncState by NAME for stable, diffable rows.
 *
 * Identity (brief 05 §3): every server/child id is a String. Baby Buddy's numeric pks are
 * stringified at the DTO boundary; PocketBase ids are 15-char strings; the on-device backend
 * mints UUIDs. `deleted` is the cloud-sync tombstone (brief 05 §5.4) — inert for backends
 * that hard-delete.
 */

@Entity(tableName = "children")
data class ChildEntity(
    @PrimaryKey val id: String,
    val firstName: String,
    val lastName: String?,
    val birthDate: String,
    val birthTime: String?,
    val slug: String,
)

@Entity(
    tableName = "events",
    indices = [
        Index(value = ["serverId"]),
        Index(value = ["childId", "startMillis"]),
    ],
)
data class EventEntity(
    /** Local identity (uuid); survives as the outbox's join key (spec §6.1). */
    @PrimaryKey val localUuid: String,
    /** Server record id; null until a CREATE op lands. Backend-native id as a string. */
    val serverId: String?,
    /** EventType.name */
    val type: String,
    val childId: String,
    /** Instant types: the event time. Timed types: start. */
    val startMillis: Long,
    /** Instant types: == startMillis. */
    val endMillis: Long,
    val feedingType: String?,
    val feedingMethod: String?,
    val amount: Double?,
    val wet: Boolean?,
    val solid: Boolean?,
    val color: String?,
    val noteText: String?,
    val milestone: String?,
    /** SyncState.name */
    val syncState: String,
    val failureReason: String?,
    /** Parsed conflict target of an overlap rejection (spec §3.8). */
    val conflictUiPath: String?,
    val conflictServerId: String?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
    /** Cloud-sync tombstone (brief 05 §5.4): deleted rows are hidden from every read. */
    val deleted: Boolean = false,
)

/** Running timers — local rows first (spec §3), server timer id attached when online. */
@Entity(tableName = "timers")
data class TimerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** EventType.name — only the four timed kinds occur. */
    val kind: String,
    val childId: String,
    val startedAtMillis: Long,
    val serverTimerId: String?,
    val createdAtMillis: Long,
)

/**
 * Outbox operation (spec §6.1). `id` order IS the sync order (oldest-first drain).
 * `payload` is JSON: for CREATE/EDIT the encoded write body (EDIT wraps it with serverId);
 * for DELETE {"serverId": n}. See data/sync/OutboxPayloads.kt.
 */
@Entity(tableName = "outbox", indices = [Index(value = ["localUuid"])])
data class OutboxOpEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** CREATE | EDIT | DELETE */
    val opType: String,
    /** EventType.name of the targeted event. */
    val entityType: String,
    val localUuid: String,
    val payload: String,
    val attempts: Int = 0,
    val lastError: String? = null,
    val createdAtMillis: Long,
)
