package app.dandle.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import app.dandle.data.api.BabyBuddyApi
import app.dandle.data.api.BaseUrlInterceptor
import app.dandle.data.auth.AuthInterceptor
import app.dandle.data.auth.LoginFlow
import app.dandle.data.auth.ServerClockInterceptor
import app.dandle.data.db.BabyBuddyDatabase
import app.dandle.data.prefs.SessionStore
import app.dandle.data.repo.ChildRepository
import app.dandle.data.repo.EventRepository
import app.dandle.data.repo.QuickActionActions
import app.dandle.data.repo.TimerRepository
import app.dandle.data.sync.OutboxDrainer
import app.dandle.data.sync.SyncWorker
import app.dandle.domain.Clock
import app.dandle.domain.ServerClock
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
 * [initDatabase] (records the context; Room builds on first use). Android supplies context/scope;
 * the rest is JVM-friendly. Network stack (OkHttp/Retrofit) and Room are lazy — nothing here is
 * constructed before the first frame actually needs it (perf: cold start).
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
    val quickActionStore = app.dandle.data.prefs.QuickActionStore(quickActionsFile)

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

    /**
     * Clients/Retrofit are lazy (perf: cold start): none of them are touched before the first
     * frame — the dashboard renders from Room and refreshes via LaunchedEffect afterwards.
     * Retrofit.create alone reflects over all 62 annotated API methods; OkHttp constructors
     * pull in the SSL stack. Deferring keeps that class-loading + reflection off app onCreate.
     */

    /** Client for the login dance: no redirects (302 = success signal), no token header. */
    val loginClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(ServerClockInterceptor(serverClock))
            .apply {
                if (app.dandle.BuildConfig.DEBUG) addInterceptor(debugLogInterceptor())
            }
            .build()
    }

    /** Client for token-authenticated API calls. */
    private val apiClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .addInterceptor(AuthInterceptor { apiKeyMirror })
            .addInterceptor(ServerClockInterceptor(serverClock))
            .addInterceptor(BaseUrlInterceptor { serverUrlMirror })
            .apply {
                if (app.dandle.BuildConfig.DEBUG) addInterceptor(debugLogInterceptor())
            }
            .build()
    }

    /**
     * BB/API request logging (debug builds only; BbLog gates output). BODY level with the
     * Authorization header redacted and every line truncated to ~2KB.
     */
    private fun debugLogInterceptor(): okhttp3.logging.HttpLoggingInterceptor {
        val interceptor = okhttp3.logging.HttpLoggingInterceptor { message ->
            app.dandle.log.BbLog.d(
                app.dandle.log.BbLog.TAG_API,
                message.take(MAX_LOG_LINE_CHARS),
            )
        }
        interceptor.level = okhttp3.logging.HttpLoggingInterceptor.Level.BODY
        interceptor.redactHeader("Authorization")
        return interceptor
    }

    val api: BabyBuddyApi by lazy {
        Retrofit.Builder()
            // Placeholder; BaseUrlInterceptor rewrites scheme/host/port from the session.
            .baseUrl("http://babybuddy.local/api/")
            .client(apiClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BabyBuddyApi::class.java)
    }

    val loginFlow: LoginFlow by lazy {
        LoginFlow(client = loginClient, json = json, sessionStore = sessionStore)
    }

    // -- Backend gateways (brief 05 §1): the only places that know the wire protocol. ----

    private val syncPusher: app.dandle.data.backend.SyncPusher by lazy {
        app.dandle.data.backend.BabyBuddySyncPusher({ api }, json)
    }

    private val puller: app.dandle.data.backend.PullGateway by lazy {
        app.dandle.data.backend.BabyBuddyPuller({ api })
    }

    private val timerMirror: app.dandle.data.backend.TimerMirror by lazy {
        app.dandle.data.backend.BabyBuddyTimerMirror({ api })
    }

    // -- Offline-first store -----------------------------------------------------

    @Volatile
    private var appContext: Context? = null

    /**
     * Room is built on first use, not in initDatabase (perf: cold start): the dashboard's first
     * frame doesn't hit the DB until its flows collect (post-first-composition), and building
     * eagerly only paid class-loading on the critical path. The connection itself was always
     * opened lazily by Room; this defers the builder + SQLite class graph too.
     */
    private val dbLazy: Lazy<BabyBuddyDatabase> = lazy {
        val context = appContext ?: error("AppContainer.initDatabase(context) was not called")
        Room.databaseBuilder(context.applicationContext, BabyBuddyDatabase::class.java, BabyBuddyDatabase.NAME)
            // T1: TRUNCATE journal, not WAL. Single-process, tiny write volume — WAL buys
            // nothing here, and its snapshot-per-read-connection semantics are the prime
            // suspect for the device-only "timeline flow serves a frozen snapshot" failure
            // (suspend reads stayed fresh, the flow did not). TRUNCATE removes that class.
            .setJournalMode(RoomDatabase.JournalMode.TRUNCATE)
            .build()
    }

    private fun db(): BabyBuddyDatabase = dbLazy.value

    /** Records the context; the database itself builds on first [db] access. Idempotent. */
    fun initDatabase(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Perf (cold start): builds the lazy singletons ([db], [api], [loginClient]) off the main
     * thread. Laziness only helps if the first touch doesn't land on the main thread mid-startup
     * — without this warm-up, the dashboard's first Room query and refresh() would pay the
     * class-loading + construction inside the content window. Called from App.onCreate's
     * application scope, so it runs in parallel with the first frame instead of blocking it.
     */
    suspend fun warmStartupSingletons() {
        db()
        loginClient
        api
    }

    val childRepository: ChildRepository by lazy { ChildRepository(puller, db()) }

    val drainer: OutboxDrainer by lazy {
        OutboxDrainer(
            pusher = syncPusher,
            json = json,
            eventDao = db().eventDao(),
            outboxDao = db().outboxDao(),
            clock = clock,
            isAuthPaused = { authExpiredMirror },
            onAuthExpired = {
                authExpiredMirror = true
                sessionStore.setSessionExpired(true)
            },
            onDrainActiveChange = { active ->
                drainActive.value = active
                // T1: a drain moves rows between sync states (PENDING→SYNCED, FAILED badges);
                // re-render the timeline on drain end even if Room's invalidation signal is lost.
                if (!active) eventRepository.nudgeTimeline()
            },
            onEntryFailed = { localUuid ->
                if (!entryFailedEvents.tryEmit(localUuid)) {
                    // Never drop the resolution signal; a small replay tail covers slow collectors.
                }
            },
        )
    }

    val eventRepository: EventRepository by lazy {
        EventRepository(
            puller = puller,
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
            timerMirror = timerMirror,
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
