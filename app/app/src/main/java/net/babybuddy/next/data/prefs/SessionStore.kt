package net.babybuddy.next.data.prefs

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path

/** Session state as persisted on disk. */
data class Session(
    val serverUrl: String?,
    val apiKey: String?,
    val username: String?,
    val activeChildId: Long?,
    val serverOffsetMs: Long,
    /**
     * True after a 401/403 during sync: the queue is paused and the user must sign in again
     * (spec §6.3.6). Cleared on successful login.
     */
    val sessionExpired: Boolean,
) {
    val isLoggedIn: Boolean get() = !apiKey.isNullOrBlank() && !serverUrl.isNullOrBlank()
}

/**
 * DataStore-backed session persistence (brief 01 §2): serverUrl, apiKey, username,
 * activeChildId, serverClockOffsetMs, sessionExpired. Constructed with a file path so the
 * class itself stays free of android.* (KMP-ready).
 */
class SessionStore(private val file: Path) {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        produceFile = { file },
    )

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key")
        val USERNAME = stringPreferencesKey("username")
        val ACTIVE_CHILD_ID = longPreferencesKey("active_child_id")
        val SERVER_OFFSET_MS = longPreferencesKey("server_offset_ms")
        val SESSION_EXPIRED = booleanPreferencesKey("session_expired")
    }

    val sessionFlow: Flow<Session> = dataStore.data.map { p ->
        Session(
            serverUrl = p[Keys.SERVER_URL],
            apiKey = p[Keys.API_KEY],
            username = p[Keys.USERNAME],
            activeChildId = p[Keys.ACTIVE_CHILD_ID],
            serverOffsetMs = p[Keys.SERVER_OFFSET_MS] ?: 0,
            sessionExpired = p[Keys.SESSION_EXPIRED] ?: false,
        )
    }

    /** Persists a successful login; clears the expired flag. */
    suspend fun saveLogin(serverUrl: String, apiKey: String, username: String) {
        dataStore.edit { p ->
            p[Keys.SERVER_URL] = serverUrl
            p[Keys.API_KEY] = apiKey
            p[Keys.USERNAME] = username
            p[Keys.SESSION_EXPIRED] = false
        }
    }

    suspend fun setActiveChild(childId: Long?) {
        dataStore.edit { p ->
            if (childId == null) p.remove(Keys.ACTIVE_CHILD_ID) else p[Keys.ACTIVE_CHILD_ID] = childId
        }
    }

    suspend fun setServerOffsetMs(offsetMs: Long) {
        dataStore.edit { it[Keys.SERVER_OFFSET_MS] = offsetMs }
    }

    suspend fun setSessionExpired(expired: Boolean) {
        dataStore.edit { it[Keys.SESSION_EXPIRED] = expired }
    }

    /** Sign out: clears credentials and session state (spec §1.2). */
    suspend fun clear() {
        dataStore.edit { p ->
            p.remove(Keys.API_KEY)
            p.remove(Keys.USERNAME)
            p.remove(Keys.ACTIVE_CHILD_ID)
            p.remove(Keys.SESSION_EXPIRED)
            // serverUrl is kept to pre-fill the field on the next login (original app behavior).
        }
    }
}
