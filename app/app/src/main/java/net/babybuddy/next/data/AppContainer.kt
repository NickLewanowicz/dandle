package net.babybuddy.next.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.babybuddy.next.data.api.BabyBuddyApi
import net.babybuddy.next.data.api.BaseUrlInterceptor
import net.babybuddy.next.data.auth.AuthInterceptor
import net.babybuddy.next.data.auth.LoginFlow
import net.babybuddy.next.data.auth.ServerClockInterceptor
import net.babybuddy.next.data.prefs.SessionStore
import net.babybuddy.next.domain.Clock
import net.babybuddy.next.domain.ServerClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Manual DI on Application (brief 00: no codegen; small app). Holds the single Json, the two
 * OkHttp clients (login vs token API), the Retrofit [BabyBuddyApi], the session store, and the
 * server clock. Everything here is JVM-friendly; Android only supplies the session file path
 * and the app scope.
 */
class AppContainer(
    private val appScope: CoroutineScope,
    sessionFile: okio.Path,
) {

    val clock: Clock = Clock.SYSTEM
    val serverClock = ServerClock(clock)

    val json: Json = Json {
        ignoreUnknownKeys = true      // server additions never crash decoding (spec §10)
        explicitNulls = false         // nulls omitted from POST/PATCH bodies (partial-write friendly)
        encodeDefaults = false
        coerceInputValues = true
    }

    val sessionStore = SessionStore(sessionFile)

    // In-memory mirrors of session state for OkHttp interceptor threads (no blocking reads).
    @Volatile
    private var apiKeyMirror: String? = null

    @Volatile
    private var serverUrlMirror: String? = null

    /** Client for the login dance: no redirects (302 = success signal), no token header. */
    val loginClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(ServerClockInterceptor(serverClock))
        .build()

    /** Client for token-authenticated API calls. */
    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor { apiKeyMirror })
        .addInterceptor(ServerClockInterceptor(serverClock))
        .addInterceptor(BaseUrlInterceptor { serverUrlMirror })
        .build()

    val api: BabyBuddyApi = Retrofit.Builder()
        // Placeholder; BaseUrlInterceptor rewrites scheme/host/port from the session.
        .baseUrl("http://babybuddy.local/api/")
        .client(apiClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BabyBuddyApi::class.java)

    val loginFlow = LoginFlow(client = loginClient, json = json, sessionStore = sessionStore)

    /** Must be called once from App.onCreate: mirrors session state + persists clock offsets. */
    fun start() {
        appScope.launch {
            val session = sessionStore.sessionFlow.first()
            serverClock.restoreOffset(session.serverOffsetMs)
        }
        serverClock.offsetListener = { offset ->
            appScope.launch { sessionStore.setServerOffsetMs(offset) }
        }
        appScope.launch {
            sessionStore.sessionFlow.collect { session ->
                apiKeyMirror = session.apiKey
                serverUrlMirror = session.serverUrl
            }
        }
    }
}
