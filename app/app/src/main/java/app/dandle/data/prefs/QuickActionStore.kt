package app.dandle.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okio.Path

/**
 * Field draft + open state of one quick-action panel (UX spec §3 "Draft persistence"): values
 * survive process death per child+type and clear only on successful save. Timer bindings live
 * in Room (TimerRepository) — only field values are stored here.
 */
@Serializable
data class PanelDraft(
    val open: Boolean = false,
    // Feeding
    val feedingType: String? = null,
    val feedingMethod: String? = null,
    // Feeding / diaper / pumping amount — kept as raw field text, parsed at save time.
    val amountText: String = "",
    // Diaper
    val wet: Boolean = false,
    val solid: Boolean = false,
    val color: String? = null,
    val extraOpen: Boolean = false,
    // Sleep / pumping / tummy time
    val noteText: String = "",
    // Tummy time
    val milestone: String = "",
    // Quick note
    val text: String = "",
    // T2: timed panels — "started X min ago" (0 = started now). Clamped by the VM.
    val startedMinutesAgo: Int = 0,
)

/** Learned last-used feeding type+method for one child (spec §3.1, spec v0.2). */
data class FeedingDefault(
    val typeWire: String,
    val methodWire: String,
) {
    companion object {
        /** First-use fallback (spec §3.1): breast milk + bottle. */
        val FALLBACK = FeedingDefault("breast milk", "bottle")
    }
}

/**
 * DataStore-backed quick-action preferences: per-child learned feeding defaults, per-child+type
 * panel drafts, and one-time UI hints. Constructed with a file path so the class stays free of
 * android.* (same pattern as SessionStore).
 */
class QuickActionStore(private val file: Path) {

    private val json = Json { ignoreUnknownKeys = true }

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        produceFile = { file },
    )

    // -- Learned feeding defaults (per child) ------------------------------------

    fun observeFeedingDefault(childId: Long): Flow<FeedingDefault> = dataStore.data.map { p ->
        FeedingDefault(
            typeWire = p[Keys.feedingType(childId)] ?: FeedingDefault.FALLBACK.typeWire,
            methodWire = p[Keys.feedingMethod(childId)] ?: FeedingDefault.FALLBACK.methodWire,
        )
    }

    suspend fun feedingDefault(childId: Long): FeedingDefault =
        observeFeedingDefault(childId).first()

    /** Persists the last-used type+method for this child (called on every feeding save). */
    suspend fun rememberFeeding(childId: Long, typeWire: String, methodWire: String) {
        dataStore.edit { p ->
            p[Keys.feedingType(childId)] = typeWire
            p[Keys.feedingMethod(childId)] = methodWire
        }
    }

    // -- Panel drafts (per child + type) ------------------------------------------

    fun observePanel(childId: Long, type: String): Flow<PanelDraft?> = dataStore.data.map { p ->
        p[Keys.panel(childId, type)]?.let(::decodeDraft)
    }

    suspend fun panel(childId: Long, type: String): PanelDraft? =
        observePanel(childId, type).first()

    suspend fun savePanel(childId: Long, type: String, draft: PanelDraft) {
        dataStore.edit { p -> p[Keys.panel(childId, type)] = json.encodeToString(draft) }
    }

    /** Successful save: the draft is consumed (spec: drafts "clear only on successful save"). */
    suspend fun clearPanel(childId: Long, type: String) {
        dataStore.edit { p -> p.remove(Keys.panel(childId, type)) }
    }

    // -- One-time hints ------------------------------------------------------------

    suspend fun wasLongPressHintShown(): Boolean =
        dataStore.data.first()[Keys.LONGPRESS_HINT_SHOWN] == true

    suspend fun setLongPressHintShown() {
        dataStore.edit { p -> p[Keys.LONGPRESS_HINT_SHOWN] = true }
    }

    // -- Sign out ------------------------------------------------------------------

    /** Sign out (spec §1.2): local drafts, learned defaults, and hints are local cache. */
    suspend fun clearAll() {
        dataStore.edit { p ->
            p.asMap().keys.toList().forEach { p.remove(it) }
        }
    }

    internal fun decodeDraft(raw: String): PanelDraft? = try {
        json.decodeFromString<PanelDraft>(raw)
    } catch (_: Exception) {
        null // corrupt/unknown draft: start clean rather than crash (spec §10)
    }

    private object Keys {
        fun feedingType(childId: Long) = stringPreferencesKey("feeding_type_$childId")
        fun feedingMethod(childId: Long) = stringPreferencesKey("feeding_method_$childId")
        fun panel(childId: Long, type: String) = stringPreferencesKey("panel_${childId}_$type")
        val LONGPRESS_HINT_SHOWN = booleanPreferencesKey("hint_longpress_shown")
    }
}
