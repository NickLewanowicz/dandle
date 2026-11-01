package app.dandle.data

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction
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

    @Volatile
    private var backendMirror: app.dandle.data.backend.Backend = app.dandle.data.backend.Backend.BABYBUDDY

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

    private val babyBuddyPusher: app.dandle.data.backend.SyncPusher by lazy {
        app.dandle.data.backend.BabyBuddySyncPusher({ api }, json)
    }

    private val babyBuddyPuller: app.dandle.data.backend.PullGateway by lazy {
        app.dandle.data.backend.BabyBuddyPuller({ api })
    }

    private val babyBuddyTimerMirror: app.dandle.data.backend.TimerMirror by lazy {
        app.dandle.data.backend.BabyBuddyTimerMirror({ api })
    }

    private val localPusher = app.dandle.data.backend.LocalSyncPusher()
    private val localPuller = app.dandle.data.backend.LocalPuller()

    /** Dandle Cloud adapter (brief 05 §5): PocketBase client + crypto-bound auth/push/pull. */
    val cloudBackend: app.dandle.data.backend.CloudBackend by lazy {
        app.dandle.data.backend.CloudBackend(
            pb = app.dandle.data.cloud.PocketBaseClient(
                baseUrl = CLOUD_BASE_URL,
                client = loginClient, // no auth interceptor: cloud calls carry their own token
                json = json,
            ),
            json = json,
        )
    }

    val cloudAuth: app.dandle.data.cloud.CloudAuth get() = cloudBackend.auth

    /**
     * Cloud child setup (brief 05 §5.4; P1 fix QA 2026-10-31): pushes the just-created local
     * child as the account's first cloud record under a client-minted record id (the id rides
     * the create so the pull-side AAD check passes), then ADOPTS that id as the child's
     * identity on this device (see [adoptCloudChildIdentity]) so events pushed later converge
     * across devices. Reads the session store, NOT the in-memory mirrors: saveCloudLogin and
     * this call can be moments apart in different coroutines, and the mirrors only update via
     * the async start() collector. No-op without a cloud session (defensive; the login flow
     * only calls this after auth). A failed push throws before any adoption — the child stays
     * local-only and the next child-setup pass pushes it again.
     */
    suspend fun pushCloudChild(child: app.dandle.domain.model.Child) {
        val session = sessionStore.sessionFlow.first()
        val token = session.cloudToken ?: return
        val userId = session.cloudUserId ?: return
        val cloudId = cloudBackend.pushChild(
            token,
            userId,
            app.dandle.data.db.ChildEntity(
                id = child.id,
                firstName = child.firstName,
                lastName = child.lastName,
                birthDate = child.birthDate,
                birthTime = child.birthTime,
                slug = child.slug,
            ),
        )
        adoptCloudChildIdentity(fromId = child.id, toId = cloudId)
    }

    /**
     * P1 identity adoption (QA 2026-10-31): after a successful child push, the cloud record
     * id becomes THE child identity on this device — the Room rows are rekeyed in ONE
     * transaction (children.id PK, events.childId, timers.childId) and the active-child
     * session pointer moves with them. This is what makes later event pushes carry the cloud
     * id in their payloads, so a second device pulls one consistent child instead of a
     * UUID/record-id split brain. Runs inline during cloud child setup BEFORE SignedIn, so no
     * concurrent writes are expected; the transaction keeps the three updates atomic anyway.
     * The DataStore pointer update follows the transaction (it cannot join a Room tx); a crash
     * between the two self-heals on the dashboard, which falls back to the first child when
     * the stored id no longer matches any row.
     */
    private suspend fun adoptCloudChildIdentity(fromId: String, toId: String) {
        if (fromId == toId) return
        val db = db()
        db.withTransaction {
            db.childDao().rekeyId(fromId, toId)
            db.eventDao().reassignChild(fromId, toId)
            db.timerDao().reassignChild(fromId, toId)
        }
        val session = sessionStore.sessionFlow.first()
        if (session.activeChildId == fromId) sessionStore.setActiveChild(toId)
        app.dandle.log.BbLog.d(
            app.dandle.log.BbLog.TAG_SYNC,
            "cloud child identity adopted: $fromId -> $toId",
        )
    }

    /**
     * Cloud child routing (P1 fix QA 2026-10-31): true when the freshly-authenticated account
     * already has children. Reads the session store for the same freshness reason as
     * [pushCloudChild]; lets backend/network errors propagate so the caller (LoginViewModel)
     * can fail OPEN into the dashboard instead of trapping a reachable account in setup.
     */
    suspend fun cloudHasChildren(): Boolean {
        val session = sessionStore.sessionFlow.first()
        return cloudBackend.hasChildren(session.cloudToken.orEmpty(), session.cloudUserId.orEmpty())
    }

    /** Android Keystore vault: DEK at rest is hardware-wrapped (brief 05 §5.2). */
    val vault: app.dandle.data.crypto.PassphraseVault by lazy {
        app.dandle.crypto.KeystoreVault()
    }

    /** Session-mirrored cloud credentials (token + user id) for per-call gateway construction. */
    @Volatile private var cloudTokenMirror: String? = null

    @Volatile private var cloudUserIdMirror: String? = null

    private fun cloudPusherOrNull(): app.dandle.data.backend.SyncPusher? {
        val token = cloudTokenMirror ?: return null
        val userId = cloudUserIdMirror ?: return null
        return cloudBackend.pusher(token, userId)
    }

    private fun cloudPullerOrNull(): app.dandle.data.backend.PullGateway? {
        val token = cloudTokenMirror ?: return null
        val userId = cloudUserIdMirror ?: return null
        return cloudBackend.puller(token, userId)
    }

    /** Per-call backend routing (brief 05 §3): the backend can change only via sign-out, but
     *  the container outlives sessions — so every dispatch re-reads the mirror. */
    private val syncPusher = object : app.dandle.data.backend.SyncPusher {
        private fun delegate(): app.dandle.data.backend.SyncPusher = when (backendMirror) {
            app.dandle.data.backend.Backend.LOCAL -> localPusher
            app.dandle.data.backend.Backend.CLOUD -> cloudPusherOrNull() ?: localPusher
            app.dandle.data.backend.Backend.BABYBUDDY -> babyBuddyPusher
        }

        override suspend fun pushCreate(type: app.dandle.domain.model.EventType, draft: app.dandle.domain.model.EventDraft) =
            delegate().pushCreate(type, draft)

        override suspend fun pushEdit(
            type: app.dandle.domain.model.EventType,
            serverId: String,
            draft: app.dandle.domain.model.EventDraft,
        ) = delegate().pushEdit(type, serverId, draft)

        override suspend fun pushDelete(type: app.dandle.domain.model.EventType, serverId: String) =
            delegate().pushDelete(type, serverId)
    }

    private val puller = object : app.dandle.data.backend.PullGateway {
        private fun delegate(): app.dandle.data.backend.PullGateway = when (backendMirror) {
            app.dandle.data.backend.Backend.LOCAL -> localPuller
            app.dandle.data.backend.Backend.CLOUD -> cloudPullerOrNull() ?: localPuller
            app.dandle.data.backend.Backend.BABYBUDDY -> babyBuddyPuller
        }

        override suspend fun listChildren(): List<app.dandle.data.db.ChildEntity> = delegate().listChildren()

        override suspend fun pullRecentEvents(limitPerType: Int): List<app.dandle.data.db.EventEntity> =
            delegate().pullRecentEvents(limitPerType)
    }

    /** CLOUD merges by LWW + tombstones; BABYBUDDY stays server-authoritative (brief 05 §5.4). */
    val lwwMerge: Boolean get() = backendMirror == app.dandle.data.backend.Backend.CLOUD

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
            .addMigrations(*app.dandle.data.db.Migrations.ALL)
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
            // One-shot drain attempt after every write. Server backends go via WorkManager
            // (collapses concurrent requests, retries with backoff when the network is down);
            // LOCAL completes inline — a CONNECTED constraint would defer a queue that needs
            // no network forever (brief 05 §4).
            requestDrain = {
                if (backendMirror == app.dandle.data.backend.Backend.LOCAL) {
                    drainer.drain()
                } else {
                    SyncWorker.enqueue(appContext ?: error("initDatabase not called"))
                }
            },
            lwwMerge = { backendMirror == app.dandle.data.backend.Backend.CLOUD },
        )
    }

    val timerRepository: TimerRepository by lazy {
        TimerRepository(
            timerMirrorProvider = {
                if (backendMirror == app.dandle.data.backend.Backend.LOCAL ||
                    backendMirror == app.dandle.data.backend.Backend.CLOUD
                ) null else babyBuddyTimerMirror
            },
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
                backendMirror = session.backend
                cloudTokenMirror = session.cloudToken
                cloudUserIdMirror = session.cloudUserId
                // Reinstall the record-cipher key after process death: the DEK comes back
                // from the Keystore-wrapped vault, never from the network (brief 05 §5.2).
                val vaultBlob = session.cloudVault
                if (session.backend == app.dandle.data.backend.Backend.CLOUD && vaultBlob != null) {
                    runCatching {
                        app.dandle.data.crypto.RecordCipher.install(vault.unwrap(unb64(vaultBlob)))
                    }
                }
            }
        }
    }

    companion object {
        /** BB/API log lines are truncated to ~2KB (review + product logging rules). */
        private const val MAX_LOG_LINE_CHARS = 2_048

        private fun unb64(text: String): ByteArray = java.util.Base64.getDecoder().decode(text)

        /** Dandle Cloud (brief 05 §5): the owner's PocketBase instance. */
        const val CLOUD_BASE_URL = "https://dandle.pockethost.io/"
    }
}
