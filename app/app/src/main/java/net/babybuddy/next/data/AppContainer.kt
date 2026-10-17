package net.babybuddy.next.data

import android.content.Context
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import net.babybuddy.next.data.api.BabyBuddyApi
import net.babybuddy.next.data.api.BaseUrlInterceptor
import net.babybuddy.next.data.auth.AuthInterceptor
import net.babybuddy.next.data.auth.LoginFlow
import net.babybuddy.next.data.auth.ServerClockInterceptor
import net.babybuddy.next.data.db.BabyBuddyDatabase
import net.babybuddy.next.data.prefs.SessionStore
import net.babybuddy.next.data.repo.ChildRepository
import net.babybuddy.next.data.repo.EventRepository
import net.babybuddy.next.data.repo.QuickActionActions
import net.babybuddy.next.data.repo.TimerRepository
import net.babybuddy.next.data.sync.OutboxDrainer
import net.babybuddy.next.data.sync.SyncWorker
import net.babybuddy.next.domain.Clock
import net.babybuddy.next.domain.ServerClock
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Manual DI on Application (brief 00: no codegen; small app). Holds the single Json, the two
 * OkHttp clients (login vs token API), the Retrofit [BabyBuddyApi], the Room database, the
 * repositories, the outbox drainer, and the session store.
 *
 * Lifecycle: construct in Application.onCreate → [start] (session mirrors + clock restore) →
 * [initDatabase] (Room + worker wiring). Android supplies context/scope; the rest is JVM-friendly.
 */
class AppContainer(
    private val appScope: CoroutineScope,
    sessionFile: okio.Path,
    quickActionsFile: okio.Path,
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

    /** Quick-action prefs: learned feeding defaults + panel drafts (Stage 2). */
    val quickActionStore = net.babybuddy.next.data.prefs.QuickActionStore(quickActionsFile)

    /** True while an outbox drain is running — feeds the SyncStatusChip (UX spec §5). */
    val drainActive = kotlinx.coroutines.flow.MutableStateFlow(false)

    /**
     * Emits the localUuid of every entry the server just REJECTED (400-class) — P1-3: the
     * dashboard surfaces the failure dialog proactively instead of waiting for a badge tap.
     */
    val entryFailedEvents = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)

    // In-memory mirrors of session state for OkHttp/worker threads (no blocking DataStore reads).
    @Volatile
    private var apiKeyMirror: String? = null

    @Volatile
    private var serverUrlMirror: String? = null

    @Volatile
    private var authExpiredMirror: Boolean = false

    /** Client for the login dance: no redirects (302 = success signal), no token header. */
    val loginClient: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .addInterceptor(ServerClockInterceptor(serverClock))
        .apply {
            if (net.babybuddy.next.BuildConfig.DEBUG) addInterceptor(debugLogInterceptor())
        }
        .build()

    /** Client for token-authenticated API calls. */
    private val apiClient: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(AuthInterceptor { apiKeyMirror })
        .addInterceptor(ServerClockInterceptor(serverClock))
        .addInterceptor(BaseUrlInterceptor { serverUrlMirror })
        .apply {
            if (net.babybuddy.next.BuildConfig.DEBUG) addInterceptor(debugLogInterceptor())
        }
        .build()

    /**
     * BB/API request logging (debug builds only; BbLog gates output). BODY level with the
     * Authorization header redacted and every line truncated to ~2KB.
     */
    private fun debugLogInterceptor(): okhttp3.logging.HttpLoggingInterceptor {
        val interceptor = okhttp3.logging.HttpLoggingInterceptor { message ->
            net.babybuddy.next.log.BbLog.d(
                net.babybuddy.next.log.BbLog.TAG_API,
                message.take(MAX_LOG_LINE_CHARS),
            )
        }
        interceptor.level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY
        interceptor.redactHeader("Authorization")
        return interceptor
    }

    val api: BabyBuddyApi = Retrofit.Builder()
        // Placeholder; BaseUrlInterceptor rewrites scheme/host/port from the session.
        .baseUrl("http://babybuddy.local/api/")
        .client(apiClient)
        .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
        .build()
        .create(BabyBuddyApi::class.java)

    val loginFlow = LoginFlow(client = loginClient, json = json, sessionStore = sessionStore)

    // -- Offline-first store -----------------------------------------------------

    @Volatile
    private var dbRef: BabyBuddyDatabase? = null

    @Volatile
    private var appContext: Context? = null

    private fun db(): BabyBuddyDatabase =
        dbRef ?: error("AppContainer.initDatabase(context) was not called")

    fun initDatabase(context: Context) {
        appContext = context.applicationContext
        if (dbRef == null) {
            dbRef = Room.databaseBuilder(context.applicationContext, BabyBuddyDatabase::class.java, BabyBuddyDatabase.NAME)
                .build()
        }
    }

    val childRepository: ChildRepository by lazy { ChildRepository({ api }, db()) }

    val drainer: OutboxDrainer by lazy {
        OutboxDrainer(
            apiProvider = { api },
            json = json,
            eventDao = db().eventDao(),
            outboxDao = db().outboxDao(),
            clock = clock,
            isAuthPaused = { authExpiredMirror },
            onAuthExpired = {
                authExpiredMirror = true
                sessionStore.setSessionExpired(true)
            },
            onDrainActiveChange = { drainActive.value = it },
            onEntryFailed = { localUuid ->
                if (!entryFailedEvents.tryEmit(localUuid)) {
                    // Never drop the resolution signal; a small replay tail covers slow collectors.
                }
            },
        )
    }

    val eventRepository: EventRepository by lazy {
        EventRepository(
            apiProvider = { api },
            db = db(),
            json = json,
            clock = clock,
            drainer = drainer,
            // One-shot drain attempt after every write, via WorkManager (collapses concurrent
            // requests, retries with backoff when the network is down).
            requestDrain = { SyncWorker.enqueue(appContext ?: error("initDatabase not called")) },
        )
    }

    val timerRepository: TimerRepository by lazy {
        TimerRepository(
            apiProvider = { api },
            db = db(),
            eventRepository = eventRepository,
            clock = clock,
            serverClock = serverClock,
        )
    }

    /** Widget-facing actions (Stage 4): DashboardViewModel semantics without the UI state. */
    val quickActionActions by lazy {
        QuickActionActions(
            timerRepository = timerRepository,
            eventRepository = eventRepository,
            quickActionStore = quickActionStore,
            childRepository = childRepository,
            sessionStore = sessionStore,
            serverClock = serverClock,
        )
    }

    /** Sign out (spec §1.2): stored key + local cache go; server data is untouched. */
    suspend fun signOut() {
        sessionStore.clear()
        childRepository.clear()
        eventRepository.clear()
        db().timerDao().clear()
        quickActionStore.clearAll()
    }

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
                authExpiredMirror = session.sessionExpired
            }
        }
    }

    companion object {
        /** BB/API log lines are truncated to ~2KB (review + product logging rules). */
        private const val MAX_LOG_LINE_CHARS = 2_048
    }
}
