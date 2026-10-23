package app.dandle.data.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Wire DTOs. Field names are EXACTLY the server's (docs/research/babybuddy-api.md §4) —
 * snake_case via @SerialName. All datetime/duration values stay Strings here: the mapper layer
 * (domain) converts; the DTO is the untouched wire shape. Unknown fields are ignored
 * (Json { ignoreUnknownKeys = true }) so server additions never crash decoding (spec §10).
 *
 * `child` is an integer primary key everywhere (research doc §1 — "not URIs").
 */

/** DRF LimitOffsetPagination envelope (research doc §3). */
@Serializable
data class PaginatedDto<T>(
    val count: Int,
    val next: String? = null,
    val previous: String? = null,
    val results: List<T> = emptyList(),
)

@Serializable
data class ChildDto(
    val id: Long,
    @SerialName("first_name") val firstName: String,
    @SerialName("last_name") val lastName: String? = null,
    @SerialName("birth_date") val birthDate: String,
    @SerialName("birth_time") val birthTime: String? = null,
    val slug: String,
)

/** Instant model — `/api/changes/` (research doc §4 "Diaper Change"). */
@Serializable
data class ChangeDto(
    val id: Long,
    val child: Long,
    val time: String,
    val wet: Boolean,
    val solid: Boolean,
    val color: String? = null,
    val amount: Double? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
)

/** Duration model — `/api/feedings/`. `type` and `method` are REQUIRED server-side choices. */
@Serializable
data class FeedingDto(
    val id: Long,
    val child: Long,
    val start: String,
    val end: String,
    val duration: String? = null,
    val type: String,
    val method: String,
    val amount: Double? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
)

/** Duration model — `/api/sleep/`. `nap` is auto-computed when omitted (research doc §4). */
@Serializable
data class SleepDto(
    val id: Long,
    val child: Long,
    val start: String,
    val end: String,
    val duration: String? = null,
    val nap: Boolean? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
)

/** Instant model — `/api/notes/`. PATCHing a note re-stamps `time` unless time is sent. */
@Serializable
data class NoteDto(
    val id: Long,
    val child: Long,
    val note: String,
    val time: String,
    val image: String? = null,
    val tags: List<String> = emptyList(),
)

/** Duration model — `/api/pumping/`. `amount` is the one REQUIRED field (research doc §8.3). */
@Serializable
data class PumpingDto(
    val id: Long,
    val child: Long,
    val start: String,
    val end: String,
    val duration: String? = null,
    val amount: Double? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
)

/** Duration model — `/api/tummy-times/`. Note text lives in `milestone`. */
@Serializable
data class TummyTimeDto(
    val id: Long,
    val child: Long,
    val start: String,
    val end: String,
    val duration: String? = null,
    val milestone: String? = null,
    val notes: String? = null,
    val tags: List<String> = emptyList(),
)

/**
 * `/api/timers/` — no `end`, no `active` field exists; "stopping" a timer is a DELETE
 * (research doc §6). `duration` is computed server-side at read time (often null on create).
 */
@Serializable
data class TimerDto(
    val id: Long,
    val child: Long? = null,
    val name: String? = null,
    val start: String? = null,
    val duration: String? = null,
    val user: Long? = null,
)

/** GET /api/profile (no trailing slash) — the only sanctioned way to fetch the API key. */
@Serializable
data class ProfileDto(
    val user: ProfileUserDto? = null,
    val language: String? = null,
    val timezone: String? = null,
    @SerialName("api_key") val apiKey: String? = null,
)

@Serializable
data class ProfileUserDto(
    val id: Long = 0,
    val username: String = "",
    @SerialName("first_name") val firstName: String? = null,
    @SerialName("last_name") val lastName: String? = null,
    val email: String? = null,
    @SerialName("is_staff") val isStaff: Boolean? = null,
)

// ---------------------------------------------------------------------------
// Write models (POST/PATCH bodies). Read-only server fields (id, duration, tags-echo,
// nap-auto) are deliberately absent — the original client strips them before POST
// (research doc §2.3). Json is configured with explicitNulls=false + default values, so
// unset optionals are omitted from the body; PATCH bodies therefore carry only the fields
// we actually set (DRF PATCH = partial merge).
// ---------------------------------------------------------------------------

@Serializable
data class ChangeWriteDto(
    val child: Long,
    val time: String? = null,
    val wet: Boolean,
    val solid: Boolean,
    val color: String? = null,
    val amount: Double? = null,
    val notes: String? = null,
)

@Serializable
data class FeedingWriteDto(
    val child: Long,
    val start: String,
    val end: String,
    val type: String,
    val method: String,
    val amount: Double? = null,
    val notes: String? = null,
)

@Serializable
data class SleepWriteDto(
    val child: Long,
    val start: String,
    val end: String,
    val notes: String? = null,
)

@Serializable
data class NoteWriteDto(
    val child: Long,
    val note: String,
    // Always sent: PATCHing a note without `time` re-stamps it server-side (research doc §4).
    val time: String,
)

@Serializable
data class PumpingWriteDto(
    val child: Long,
    val start: String,
    val end: String,
    // Server-required (research doc §4): the UI defaults it to 0.0, never null.
    val amount: Double,
    val notes: String? = null,
)

@Serializable
data class TummyTimeWriteDto(
    val child: Long,
    val start: String,
    val end: String,
    val milestone: String? = null,
    val notes: String? = null,
)

@Serializable
data class TimerWriteDto(
    val child: Long? = null,
    val name: String? = null,
    val start: String? = null,
)
