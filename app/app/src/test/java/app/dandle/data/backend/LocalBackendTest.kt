package app.dandle.data.backend

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import app.dandle.data.backend.PullGateway
import app.dandle.data.db.BabyBuddyDatabase
import app.dandle.data.db.EventEntity
import app.dandle.data.db.OutboxOpEntity
import app.dandle.data.sync.OutboxDrainer
import app.dandle.domain.Clock
import app.dandle.domain.model.SyncState
import app.dandle.domain.model.EventDraft
import app.dandle.domain.model.EventType
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * On-device backend semantics (brief 05 §4): rows are born synced with a minted id (the
 * "synced ⇒ serverId != null" invariant — otherwise every edit would re-take the CREATE
 * branch), the drain completes with NO network, and the unsynced counters stay at zero.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class LocalBackendTest {

    private lateinit var db: BabyBuddyDatabase
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val now = 1_760_000_000_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BabyBuddyDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun drainer() = OutboxDrainer(
        pusher = LocalSyncPusher(),
        json = json,
        eventDao = db.eventDao(),
        outboxDao = db.outboxDao(),
        clock = Clock.fixed(now),
        isAuthPaused = { false },
        onAuthExpired = {},
    )

    private fun changeDraft() = EventDraft(
        type = EventType.CHANGE,
        childId = "child-1",
        startMillis = now,
        endMillis = now,
        wet = true,
        solid = false,
    )

    @Test
    fun `create drains inline with no network and mints a server id (edit-proof identity)`() = runTest {
        val repo = repository()
        val created = repo.create(changeDraft())

        val row = db.eventDao().byLocalUuid(created.localUuid)!!
        assertEquals(SyncState.SYNCED, SyncState.valueOf(row.syncState))
        assertNotNull("LOCAL rows MUST carry a non-null serverId", row.serverId)
        assertEquals("no network ops linger in the outbox", 0, db.outboxDao().snapshot().size)
        assertEquals("unsynced counters stay 0 (no permanent offline chip)", 0, repo.observeUnsentCount().let { it.first() })
    }

    @Test
    fun `edit of a LOCAL-synced row takes the EDIT branch, never re-CREATEs`() = runTest {
        val repo = repository()
        val created = repo.create(changeDraft())
        val originalId = db.eventDao().byLocalUuid(created.localUuid)!!.serverId

        repo.edit(created.localUuid, changeDraft().copy(solid = true))
        advanceDrain()

        val row = db.eventDao().byLocalUuid(created.localUuid)!!
        assertEquals("identity is stable across edits", originalId, row.serverId)
        assertEquals(SyncState.SYNCED, SyncState.valueOf(row.syncState))
        assertEquals("exactly one row for one logical entry", 1, countEvents())
    }

    @Test
    fun `delete of a LOCAL-synced row removes it and completes the op`() = runTest {
        val repo = repository()
        val created = repo.create(changeDraft())
        repo.delete(created.localUuid)
        advanceDrain()
        assertNull(db.eventDao().byLocalUuid(created.localUuid))
        assertEquals(0, db.outboxDao().snapshot().size)
    }

    @Test
    fun `pull reports success with nothing to do (LOCAL never shows offline)`() = runTest {
        val result = repository().refresh()
        assertTrue(result is app.dandle.data.repo.EventRepository.RefreshResult.Success)
    }

    // -- Cloud LWW merge (brief 05 §5.4): same repo, lwwMerge=true ------------------

    @Test
    fun `LWW merge keeps the newer local edit and adopts tombstones`() = runTest {
        val repo = repository(lww = true)
        // Local SYNCED row edited at t=200 (newer).
        db.eventDao().upsertAll(listOf(serverEvent("srv1", updatedAt = 200).copy(
            localUuid = "local-newer",
            serverId = "srv1",
            syncState = SyncState.SYNCED.name,
        )))
        // Server pull carries the OLDER copy (client_updated=100).
        pulledRows = listOf(serverEvent("srv1", updatedAt = 100))

        val result = repo.refresh()
        assertTrue(result is app.dandle.data.repo.EventRepository.RefreshResult.Success)
        val row = db.eventDao().byServerId("CHANGE", "srv1")!!
        assertEquals("the newer LOCAL write wins the merge", 200L, row.updatedAtMillis)
        assertEquals("local-newer", row.localUuid)
        assertEquals("note survives the stale pull", true, row.wet)
    }

    @Test
    fun `a cloud tombstone hides the row and an older pull cannot resurrect it`() = runTest {
        val repo = repository(lww = true)
        // Live row pulled first (t=300)…
        pulledRows = listOf(serverEvent("srv2", updatedAt = 300))
        assertTrue(repo.refresh() is app.dandle.data.repo.EventRepository.RefreshResult.Success)
        assertEquals(1, db.eventDao().timeline("child-1").size)

        // …then the record is tombstoned (t=400): the pull adopts it and the row hides.
        pulledRows = listOf(serverEvent("srv2", updatedAt = 400).copy(deleted = true))
        assertTrue(repo.refresh() is app.dandle.data.repo.EventRepository.RefreshResult.Success)
        assertTrue("tombstoned row vanishes from the timeline", db.eventDao().timeline("child-1").isEmpty())
        assertEquals("tombstones do not count as unsynced", 0, repo.observeUnsentCount().first())

        // A STALE (older, live) pull must not resurrect the tombstoned row.
        pulledRows = listOf(serverEvent("srv2", updatedAt = 350))
        assertTrue(repo.refresh() is app.dandle.data.repo.EventRepository.RefreshResult.Success)
        assertEquals("stale pull loses to the tombstone clock", 0, db.eventDao().timeline("child-1").size)
    }

    // -- Helpers --------------------------------------------------------------------

    /** Rows the (fake) puller serves to the next refresh — LWW tests script these. */
    @Volatile private var pulledRows: List<EventEntity> = emptyList()

    private fun repository(lww: Boolean = false) = app.dandle.data.repo.EventRepository(
        puller = object : PullGateway {
            override suspend fun listChildren(): List<app.dandle.data.db.ChildEntity> = emptyList()
            override suspend fun pullRecentEvents(limitPerType: Int): List<EventEntity> = pulledRows
        },
        db = db,
        json = json,
        clock = Clock.fixed(now),
        drainer = drainer(),
        // Production parity: LOCAL routes the write-path drain INLINE (AppContainer wiring).
        requestDrain = { drainer().drain() },
        lwwMerge = { lww },
    )

    private suspend fun advanceDrain() {
        drainer().drain()
    }

    private suspend fun countEvents(): Int = db.eventDao().timeline("child-1").size

    private fun serverEvent(id: String, updatedAt: Long) = EventEntity(
        localUuid = "server:CHANGE:$id",
        serverId = id,
        type = "CHANGE",
        childId = "child-1",
        startMillis = now,
        endMillis = now,
        feedingType = null, feedingMethod = null, amount = null,
        wet = true, solid = false, color = null, noteText = null, milestone = null,
        syncState = SyncState.SYNCED.name,
        failureReason = null, conflictUiPath = null, conflictServerId = null,
        createdAtMillis = 0,
        updatedAtMillis = updatedAt,
        deleted = false,
    )
}
