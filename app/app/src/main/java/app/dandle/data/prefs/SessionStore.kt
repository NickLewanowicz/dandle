package app.dandle.data.prefs

import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import app.dandle.data.backend.Backend
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okio.Path

/** Session state as persisted on disk. */
data class Session(
    val serverUrl: String?,
    val apiKey: String?,
    val username: String?,
    val activeChildId: String?,
    val serverOffsetMs: Long,
    /**
     * True after a 401/403 during sync: the queue is paused and the user must sign in again
     * (spec §6.3.6). Cleared on successful login.
     */
    val sessionExpired: Boolean,
    /** Which backend this install uses (brief 05 §3); default BABYBUDDY for pre-05 sessions. */
    val backend: Backend,
    /** LOCAL mode: true once the child-setup wizard completed (there is nothing else to log in). */
    val initialized: Boolean,
    /** CLOUD mode: PocketBase auth token + the stored DEK envelope (wrapped by the passphrase). */
    val cloudToken: String?,
    val cloudEnvelope: String?,
    val cloudUsername: String?,
    val cloudUserId: String?,
    /** Keystore-wrapped DEK (opaque; unwrapping is the android layer's job). */
    val cloudVault: String?,
) {
    val isLoggedIn: Boolean
        get() = when (backend) {
            Backend.LOCAL -> initialized
            Backend.CLOUD -> !cloudToken.isNullOrBlank()
            Backend.BABYBUDDY -> !apiKey.isNullOrBlank() && !serverUrl.isNullOrBlank()
        }
}

/**
 * DataStore-backed session persistence (brief 01 §2, brief 05 §3): serverUrl, apiKey,
 * username, activeChildId, serverClockOffsetMs, sessionExpired, backend selection, and the
 * cloud session shape. Constructed with a file path so the class itself stays free of
 * android.* (KMP-ready).
 */
class SessionStore(private val file: Path) {

    private val dataStore: DataStore<Preferences> = PreferenceDataStoreFactory.createWithPath(
        migrations = listOf(ACTIVE_CHILD_ID_LONG_TO_STRING),
        produceFile = { file },
    )

    private object Keys {
        val SERVER_URL = stringPreferencesKey("server_url")
        val API_KEY = stringPreferencesKey("api_key")
        val USERNAME = stringPreferencesKey("username")
        val ACTIVE_CHILD_ID = stringPreferencesKey("active_child_id_s")
        val SERVER_OFFSET_MS = longPreferencesKey("server_offset_ms")
        val SESSION_EXPIRED = booleanPreferencesKey("session_expired")
        val BACKEND = stringPreferencesKey("backend")
        val INITIALIZED = booleanPreferencesKey("backend_initialized")
        val CLOUD_TOKEN = stringPreferencesKey("cloud_token")
        val CLOUD_ENVELOPE = stringPreferencesKey("cloud_envelope")
        val CLOUD_USERNAME = stringPreferencesKey("cloud_username")
        val CLOUD_USER_ID = stringPreferencesKey("cloud_user_id")
        val CLOUD_VAULT = stringPreferencesKey("cloud_vault")
    }

    val sessionFlow: Flow<Session> = dataStore.data.map { p ->
        Session(
            serverUrl = p[Keys.SERVER_URL],
            apiKey = p[Keys.API_KEY],
            username = p[Keys.USERNAME],
            activeChildId = p[Keys.ACTIVE_CHILD_ID],
            serverOffsetMs = p[Keys.SERVER_OFFSET_MS] ?: 0,
            sessionExpired = p[Keys.SESSION_EXPIRED] ?: false,
            backend = p[Keys.BACKEND]?.let { runCatching { Backend.valueOf(it) }.getOrNull() } ?: Backend.BABYBUDDY,
            initialized = p[Keys.INITIALIZED] ?: false,
            cloudToken = p[Keys.CLOUD_TOKEN],
            cloudEnvelope = p[Keys.CLOUD_ENVELOPE],
            cloudUsername = p[Keys.CLOUD_USERNAME],
            cloudUserId = p[Keys.CLOUD_USER_ID],
            cloudVault = p[Keys.CLOUD_VAULT],
        )
    }

    /** Persists a successful Baby Buddy login; selects the backend and clears the expired flag. */
    suspend fun saveLogin(serverUrl: String, apiKey: String, username: String) {
        dataStore.edit { p ->
            p[Keys.BACKEND] = Backend.BABYBUDDY.name
            p[Keys.SERVER_URL] = serverUrl
            p[Keys.API_KEY] = apiKey
            p[Keys.USERNAME] = username
            p[Keys.SESSION_EXPIRED] = false
        }
    }

    /** Persists a successful cloud login (brief 05 §5). */
    suspend fun saveCloudLogin(token: String, envelope: String, username: String, userId: String, vault: String) {
        dataStore.edit { p ->
            p[Keys.BACKEND] = Backend.CLOUD.name
            p[Keys.CLOUD_TOKEN] = token
            p[Keys.CLOUD_ENVELOPE] = envelope
            p[Keys.CLOUD_USERNAME] = username
            p[Keys.CLOUD_USER_ID] = userId
            p[Keys.CLOUD_VAULT] = vault
            p[Keys.SESSION_EXPIRED] = false
        }
    }

    /** Persists the on-device backend (brief 05 §4) — child setup completion is the "login". */
    suspend fun saveDeviceSession() {
        dataStore.edit { p ->
            p[Keys.BACKEND] = Backend.LOCAL.name
            p[Keys.INITIALIZED] = true
            p[Keys.SESSION_EXPIRED] = false
        }
    }

    suspend fun setActiveChild(childId: String?) {
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
            p.remove(Keys.BACKEND)
            p.remove(Keys.INITIALIZED)
            p.remove(Keys.CLOUD_TOKEN)
            p.remove(Keys.CLOUD_ENVELOPE)
            p.remove(Keys.CLOUD_USERNAME)
            p.remove(Keys.CLOUD_USER_ID)
            p.remove(Keys.CLOUD_VAULT)
            // serverUrl is kept to pre-fill the field on the next login (original app behavior).
        }
    }

    /**
     * Brief 05 §3: child ids went Long → String (multi-backend identity). One-time DataStore
     * migration rewrites the old long key into the NEW string key — which MUST have a
     * different NAME: Preferences keys compare equal by name alone, so reusing
     * "active_child_id" for both types makes shouldMigrate true forever and the next launch
     * ClassCastExceptions reading the (now-String) value as Long (upgrade crash loop found
     * on the emulator, 2026-11-01).
     */
    private companion object {
        val OLD_ACTIVE_CHILD_ID = longPreferencesKey("active_child_id")

        val ACTIVE_CHILD_ID_LONG_TO_STRING = object : DataMigration<Preferences> {
            override suspend fun shouldMigrate(current: Preferences): Boolean =
                current.asMap().containsKey(OLD_ACTIVE_CHILD_ID)

            override suspend fun migrate(current: Preferences): Preferences {
                // Type-safe read: the broken first cut of this migration could leave a STRING
                // under the old key name (crash loop) — heal that too instead of CCE-ing.
                val raw = current.asMap()[OLD_ACTIVE_CHILD_ID] ?: return current
                val converted = when (raw) {
                    is Number -> raw.toString()
                    is String -> raw
                    else -> return current
                }
                return current.toMutablePreferences().apply {
                    remove(OLD_ACTIVE_CHILD_ID)
                    set(Keys.ACTIVE_CHILD_ID, converted)
                }.toPreferences()
            }

            override suspend fun cleanUp() { /* old key already removed in migrate */ }
        }
    }
}
