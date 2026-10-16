package net.babybuddy.next.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schema decisions (brief 01 §5 asked for a justified choice):
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
 */

@Entity(tableName = "children")
data class ChildEntity(
    @PrimaryKey val id: Long,
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
    /** Server pk; null until a CREATE op lands. */
    val serverId: Long?,
    /** EventType.name */
    val type: String,
    val childId: Long,
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
    val conflictServerId: Long?,
    val createdAtMillis: Long,
    val updatedAtMillis: Long,
)

/** Running timers — local rows first (spec §3), server timer id attached when online. */
@Entity(tableName = "timers")
data class TimerEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** EventType.name — only the four timed kinds occur. */
    val kind: String,
    val childId: Long,
    val startedAtMillis: Long,
    val serverTimerId: Long?,
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
