package app.dandle.data.mapper

import app.dandle.data.api.ChangeDto
import app.dandle.data.api.ChangeWriteDto
import app.dandle.data.api.ChildDto
import app.dandle.data.api.FeedingDto
import app.dandle.data.api.FeedingWriteDto
import app.dandle.data.api.NoteDto
import app.dandle.data.api.NoteWriteDto
import app.dandle.data.api.PumpingDto
import app.dandle.data.api.PumpingWriteDto
import app.dandle.data.api.SleepDto
import app.dandle.data.api.SleepWriteDto
import app.dandle.data.api.TimerDto
import app.dandle.data.api.TummyTimeDto
import app.dandle.data.api.TummyTimeWriteDto
import app.dandle.data.db.ChildEntity
import app.dandle.data.db.EventEntity
import app.dandle.data.db.TimerEntity
import app.dandle.domain.Iso8601
import app.dandle.domain.model.SyncState
import app.dandle.domain.model.Child
import app.dandle.domain.model.Event
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType
import app.dandle.domain.model.RunningTimer

/**
 * Mappers between the three representations: wire DTOs (exact server fields), Room entities
 * (offline-first source of truth), and domain models (what the UI sees).
 *
 * These live in data/ (not domain/) on purpose: they translate between two data-layer shapes,
 * keeping domain/ free of any data-layer imports (brief 00 module shape).
 */

// -- Child -------------------------------------------------------------------

fun ChildDto.toEntity() = ChildEntity(
    id = id.toString(),
    firstName = firstName,
    lastName = lastName,
    birthDate = birthDate,
    birthTime = birthTime,
    slug = slug,
)

fun ChildEntity.toModel() = Child(
    id = id,
    firstName = firstName,
    lastName = lastName,
    slug = slug,
    birthDate = birthDate,
    birthTime = birthTime,
)

// -- Event: DTO → entity (server rows arrive SYNCED) ----------------------------

private const val SERVER_UUID_PREFIX = "server:"

/** Synthetic stable local id for server-sourced rows (merge key during refresh). */
fun serverLocalUuid(type: EventType, serverId: String) = "$SERVER_UUID_PREFIX${type.name}:$serverId"

fun ChangeDto.toEntity() = EventEntity(
    localUuid = serverLocalUuid(EventType.CHANGE, id.toString()),
    serverId = id.toString(),
    type = EventType.CHANGE.name,
    childId = child.toString(),
    startMillis = Iso8601.parse(time),
    endMillis = Iso8601.parse(time),
    feedingType = null, feedingMethod = null, amount = amount,
    wet = wet, solid = solid, color = color,
    noteText = notes, milestone = null,
    syncState = SyncState.SYNCED.name,
    failureReason = null, conflictUiPath = null, conflictServerId = null,
    createdAtMillis = 0, updatedAtMillis = 0,
)

fun FeedingDto.toEntity() = EventEntity(
    localUuid = serverLocalUuid(EventType.FEEDING, id.toString()),
    serverId = id.toString(),
    type = EventType.FEEDING.name,
    childId = child.toString(),
    startMillis = Iso8601.parse(start),
    endMillis = Iso8601.parse(end),
    feedingType = type, feedingMethod = method, amount = amount,
    wet = null, solid = null, color = null,
    noteText = notes, milestone = null,
    syncState = SyncState.SYNCED.name,
    failureReason = null, conflictUiPath = null, conflictServerId = null,
    createdAtMillis = 0, updatedAtMillis = 0,
)

fun SleepDto.toEntity() = EventEntity(
    localUuid = serverLocalUuid(EventType.SLEEP, id.toString()),
    serverId = id.toString(),
    type = EventType.SLEEP.name,
    childId = child.toString(),
    startMillis = Iso8601.parse(start),
    endMillis = Iso8601.parse(end),
    feedingType = null, feedingMethod = null, amount = null,
    wet = null, solid = null, color = null,
    noteText = notes, milestone = null,
    syncState = SyncState.SYNCED.name,
    failureReason = null, conflictUiPath = null, conflictServerId = null,
    createdAtMillis = 0, updatedAtMillis = 0,
)

fun NoteDto.toEntity() = EventEntity(
    localUuid = serverLocalUuid(EventType.NOTE, id.toString()),
    serverId = id.toString(),
    type = EventType.NOTE.name,
    childId = child.toString(),
    startMillis = Iso8601.parse(time),
    endMillis = Iso8601.parse(time),
    feedingType = null, feedingMethod = null, amount = null,
    wet = null, solid = null, color = null,
    noteText = note, milestone = null,
    syncState = SyncState.SYNCED.name,
    failureReason = null, conflictUiPath = null, conflictServerId = null,
    createdAtMillis = 0, updatedAtMillis = 0,
)

fun PumpingDto.toEntity() = EventEntity(
    localUuid = serverLocalUuid(EventType.PUMPING, id.toString()),
    serverId = id.toString(),
    type = EventType.PUMPING.name,
    childId = child.toString(),
    startMillis = Iso8601.parse(start),
    endMillis = Iso8601.parse(end),
    feedingType = null, feedingMethod = null, amount = amount,
    wet = null, solid = null, color = null,
    noteText = notes, milestone = null,
    syncState = SyncState.SYNCED.name,
    failureReason = null, conflictUiPath = null, conflictServerId = null,
    createdAtMillis = 0, updatedAtMillis = 0,
)

fun TummyTimeDto.toEntity() = EventEntity(
    localUuid = serverLocalUuid(EventType.TUMMY_TIME, id.toString()),
    serverId = id.toString(),
    type = EventType.TUMMY_TIME.name,
    childId = child.toString(),
    startMillis = Iso8601.parse(start),
    endMillis = Iso8601.parse(end),
    feedingType = null, feedingMethod = null, amount = null,
    wet = null, solid = null, color = null,
    noteText = notes, milestone = milestone,
    syncState = SyncState.SYNCED.name,
    failureReason = null, conflictUiPath = null, conflictServerId = null,
    createdAtMillis = 0, updatedAtMillis = 0,
)

// -- Entity ↔ domain -------------------------------------------------------------

fun EventEntity.toModel() = Event(
    localUuid = localUuid,
    serverId = serverId,
    type = EventType.valueOf(type),
    childId = childId,
    startMillis = startMillis,
    endMillis = endMillis,
    feedingType = feedingType,
    feedingMethod = feedingMethod,
    amount = amount,
    wet = wet,
    solid = solid,
    color = color,
    noteText = noteText,
    milestone = milestone,
    syncState = SyncState.valueOf(syncState),
    failureReason = failureReason,
    conflictUiPath = conflictUiPath,
    conflictServerId = conflictServerId,
    createdAtMillis = createdAtMillis,
    updatedAtMillis = updatedAtMillis,
)

// -- Domain draft → write DTO (exact server fields; ISO UTC stamps) ----------------

fun EventDraft.toChangeWrite() = ChangeWriteDto(
    child = childId.toLong(),
    time = Iso8601.format(startMillis),
    wet = wet ?: false,
    solid = solid ?: false,
    color = color,
    amount = amount,
    notes = noteText,
)

fun EventDraft.toFeedingWrite() = FeedingWriteDto(
    child = childId.toLong(),
    start = Iso8601.format(startMillis),
    end = Iso8601.format(endMillis),
    type = feedingType ?: "breast milk",
    method = feedingMethod ?: "bottle",
    amount = amount,
    notes = noteText,
)

fun EventDraft.toSleepWrite() = SleepWriteDto(
    child = childId.toLong(),
    start = Iso8601.format(startMillis),
    end = Iso8601.format(endMillis),
    notes = noteText,
)

fun EventDraft.toNoteWrite() = NoteWriteDto(
    child = childId.toLong(),
    note = noteText.orEmpty(),
    // Always stamped: PATCHing a note without `time` re-stamps it server-side (research doc §4).
    time = Iso8601.format(startMillis),
)

fun EventDraft.toPumpingWrite() = PumpingWriteDto(
    child = childId.toLong(),
    start = Iso8601.format(startMillis),
    end = Iso8601.format(endMillis),
    amount = amount ?: 0.0, // server-required (research doc §4); UI defaults to 0.0
    notes = noteText,
)

fun EventDraft.toTummyTimeWrite() = TummyTimeWriteDto(
    child = childId.toLong(),
    start = Iso8601.format(startMillis),
    end = Iso8601.format(endMillis),
    milestone = milestone,
    notes = noteText,
)

// -- Timers -----------------------------------------------------------------------

fun TimerDto.toEntity(nowMillis: Long) = TimerEntity(
    id = 0,
    kind = EventType.FEEDING.name, // overwritten by the caller, which knows the matched kind
    childId = (child ?: 0).toString(),
    startedAtMillis = start?.let { runCatching { Iso8601.parse(it) }.getOrDefault(nowMillis) } ?: nowMillis,
    serverTimerId = id.toString(),
    createdAtMillis = nowMillis,
)

fun TimerEntity.toModel() = RunningTimer(
    localId = id,
    kind = EventType.valueOf(kind),
    childId = childId,
    startedAtMillis = startedAtMillis,
    serverTimerId = serverTimerId,
)
