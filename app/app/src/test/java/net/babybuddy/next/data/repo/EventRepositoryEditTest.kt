package net.babybuddy.next.data.repo

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import net.babybuddy.next.data.api.BabyBuddyApi
import net.babybuddy.next.data.db.BabyBuddyDatabase
import net.babybuddy.next.data.sync.OutboxDrainer
import net.babybuddy.next.domain.Clock
import net.babybuddy.next.domain.model.EventDraft
import net.babybuddy.next.domain.model.EventType
import net.babybuddy.next.domain.model.SyncState
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory

/**
 * Quick Edit's offline contract (spec §5/§6): edits apply locally immediately, supersede stale
 * queued CREATE payloads, replay as PATCH for synced entries, and deletes of never-synced rows
 * drop the queued op entirely.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class EventRepositoryEditTest {

    private lateinit var db: BabyBuddyDatabase
    private lateinit var server: MockWebServer
    private lateinit var api: BabyBuddyApi
    private lateinit var repository: EventRepository
    private lateinit var drainer: OutboxDrainer

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; encodeDefaults = false }
    private val now = 1_760_000_000_000L
    private val responses = ArrayDeque<MockResponse>()

    /** "METHOD path [+ body]" per request, in arrival order. */
    private val seen = mutableListOf<String>()

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BabyBuddyDatabase::class.java).build()
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8()
                seen += if (body.isBlank()) {
                    "${request.method} ${request.requestUrl!!.encodedPath}"
                } else {
                    "${request.method} ${request.requestUrl!!.encodedPath} $body"
                }
                return responses.removeFirstOrNull()
                    ?: MockResponse().setResponseCode(500).setBody("""{"detail":"unscripted"}""")
            }
        }
        server.start()
        api = Retrofit.Builder()
            .baseUrl(server.url("/api/"))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(BabyBuddyApi::class.java)
        drainer = OutboxDrainer(
            apiProvider = { api },
            json = json,
            eventDao = db.eventDao(),
            outboxDao = db.outboxDao(),
            clock = Clock.fixed(now),
            isAuthPaused = { false },
            onAuthExpired = {},
        )
        repository = EventRepository(
            apiProvider = { api },
            db = db,
            json = json,
            clock = Clock.fixed(now),
            drainer = drainer,
            requestDrain = { /* under manual control */ },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    /**
     * A 2xx body the DTO layer can actually decode (read models require their fields — e.g.
     * SleepDto needs start/end, FeedingDto needs type/method).
     */
    private fun ok(type: EventType, id: Long): MockResponse {
        val time = "2025-10-09T05:00:00Z"
        val fields = when (type) {
            EventType.CHANGE -> """"time":"$time","wet":true,"solid":false"""
            EventType.FEEDING -> """"start":"$time","end":"$time","type":"breast milk","method":"bottle","amount":1.0"""
            EventType.SLEEP -> """"start":"$time","end":"$time","notes":null"""
            EventType.NOTE -> """"note":"x","time":"$time""""
            EventType.PUMPING -> """"start":"$time","end":"$time","amount":0.0"""
            EventType.TUMMY_TIME -> """"start":"$time","end":"$time","milestone":null"""
        }
        return MockResponse().setResponseCode(200).setBody("""{"id":$id,"child":1,$fields}""")
    }

    /** The JSON body of the single recorded request (JSON starts at the first '{'). */
    private fun singleBody(): kotlinx.serialization.json.JsonObject {
        val rest = seen.single().substringAfter("{", missingDelimiterValue = "")
        return json.parseToJsonElement("{$rest").jsonObject
    }

    // -- Edit supersedes a queued CREATE ------------------------------------------------

    @Test
    fun `edit while create is still queued replaces the payload - only the final body posts`() = runTest {
        val created = repository.create(sleepDraft(noteText = "first"))
        // Still offline: CREATE op queued.
        assertEquals(1, db.outboxDao().snapshot().size)

        repository.edit(created.localUuid, sleepDraft(noteText = "edited", startShift = 60_000))

        val ops = db.outboxDao().snapshot()
        assertEquals("stale CREATE is dropped, one fresh op remains", 1, ops.size)
        assertEquals("CREATE", ops.single().opType)
        val body = json.parseToJsonElement(ops.single().payload).jsonObject
        assertEquals("edited", body["notes"]!!.jsonPrimitive.content)

        // Drain: exactly one POST, carrying the EDITED body.
        responses += ok(EventType.SLEEP, 77)
        assertEquals(OutboxDrainer.Outcome.Completed, drainer.drain())
        assertEquals(1, seen.size)
        assertTrue(seen.single().startsWith("POST /api/sleep/"))
        assertTrue(seen.single().contains(""""notes":"edited""""))
        val row = db.eventDao().byLocalUuid(created.localUuid)!!
        assertEquals(77L, row.serverId)
        assertEquals(SyncState.SYNCED.name, row.syncState)
        assertEquals(0, db.outboxDao().snapshot().size)
    }

    @Test
    fun `edit while create queued also updates the local row immediately`() = runTest {
        val created = repository.create(feedingDraft())
        repository.edit(
            created.localUuid,
            feedingDraft(amount = 200.0, methodWire = "left breast"),
        )
        val row = db.eventDao().byLocalUuid(created.localUuid)!!
        assertEquals(200.0, row.amount!!, 0.0)
        assertEquals("left breast", row.feedingMethod)
        assertEquals(SyncState.PENDING.name, row.syncState)
        assertNull(row.serverId)
    }

    // -- Edit of a synced entry replays as PATCH ------------------------------------------

    @Test
    fun `edit of a synced entry queues an EDIT op and PATCHes with the new times`() = runTest {
        responses += ok(EventType.SLEEP, 5)
        val created = repository.create(sleepDraft())
        drainer.drain()
        seen.clear()

        repository.edit(
            created.localUuid,
            sleepDraft(startShift = -15 * 60_000, noteText = "moved"),
        )
        assertEquals("EDIT", db.outboxDao().snapshot().single().opType)

        responses += ok(EventType.SLEEP, 5)
        drainer.drain()
        val patch = seen.single()
        assertTrue(patch.startsWith("PATCH /api/sleep/5/"))
        assertTrue(patch.contains(""""notes":"moved""""))
        assertEquals(SyncState.SYNCED.name, db.eventDao().byLocalUuid(created.localUuid)!!.syncState)
    }

    // -- Delete while pending --------------------------------------------------------------

    @Test
    fun `delete of a never-synced entry drops the queued op and removes the row`() = runTest {
        val created = repository.create(sleepDraft())
        assertEquals(1, db.outboxDao().snapshot().size)

        repository.delete(created.localUuid)

        assertNull(db.eventDao().byLocalUuid(created.localUuid))
        assertEquals(0, db.outboxDao().snapshot().size)
        assertTrue(seen.isEmpty()) // nothing ever reached the server
    }

    @Test
    fun `delete of a synced entry queues a server DELETE`() = runTest {
        responses += ok(EventType.SLEEP, 9)
        val created = repository.create(sleepDraft())
        drainer.drain()
        seen.clear()

        repository.delete(created.localUuid)
        responses += MockResponse().setResponseCode(204)
        drainer.drain()

        assertNull(db.eventDao().byLocalUuid(created.localUuid))
        assertEquals(listOf("DELETE /api/sleep/9/"), seen)
    }

    // -- FAILED resolution via edit -----------------------------------------------------

    @Test
    fun `editing a FAILED never-synced entry resets it to PENDING with a fresh CREATE`() = runTest {
        responses += MockResponse().setResponseCode(400).setBody(
            """{"non_field_errors":["Another entry intersects the specified time period."]}""",
        )
        val created = repository.create(sleepDraft())
        drainer.drain() // rejected → FAILED
        assertEquals(SyncState.FAILED.name, db.eventDao().byLocalUuid(created.localUuid)!!.syncState)

        // Parent fixes the times: local-first edit → PENDING again, old op replaced.
        repository.edit(created.localUuid, sleepDraft(startShift = 30 * 60_000))
        val row = db.eventDao().byLocalUuid(created.localUuid)!!
        assertEquals(SyncState.PENDING.name, row.syncState)
        assertNull(row.failureReason)

        responses += ok(EventType.SLEEP, 12)
        drainer.drain()
        assertEquals(SyncState.SYNCED.name, db.eventDao().byLocalUuid(created.localUuid)!!.syncState)
    }

    // -- Edit draft round-trips per type --------------------------------------------------

    @Test
    fun `edited drafts round-trip every field for all six types`() = runTest {
        val cases = listOf(
            EventType.FEEDING to feedingDraft(
                typeWire = "formula",
                methodWire = "bottle",
                amount = 150.0,
                noteText = "spit up after",
            ),
            EventType.CHANGE to EventDraft(
                type = EventType.CHANGE,
                childId = 1,
                startMillis = now,
                endMillis = now,
                wet = true,
                solid = true,
                color = "yellow",
                amount = 2.5,
                noteText = "seedy",
            ),
            EventType.SLEEP to sleepDraft(noteText = "night stretch"),
            EventType.NOTE to EventDraft(
                type = EventType.NOTE,
                childId = 1,
                startMillis = now,
                endMillis = now,
                noteText = "first words!",
            ),
            EventType.PUMPING to EventDraft(
                type = EventType.PUMPING,
                childId = 1,
                startMillis = now,
                endMillis = now + 1,
                amount = 85.0,
                noteText = "left side",
            ),
            EventType.TUMMY_TIME to EventDraft(
                type = EventType.TUMMY_TIME,
                childId = 1,
                startMillis = now,
                endMillis = now + 2,
                milestone = "Rolled over!",
                noteText = "proud moment",
            ),
        )
        for ((type, draft) in cases) {
            // Synced row first so the edit goes through the EDIT/PATCH path.
            responses += ok(type, 1)
            val created = repository.create(draft)
            drainer.drain()
            seen.clear()

            val edited = draft.copy(
                startMillis = draft.startMillis - 60_000,
                endMillis = draft.endMillis - 60_000,
            )
            repository.edit(created.localUuid, edited)
            responses += ok(type, 1)
            drainer.drain()

            // Assert the PATCH carried the edited type-specific fields.
            assertTrue("expected a PATCH, saw: $seen", seen.single().startsWith("PATCH /api/${type.apiPath}/1/"))
            val bodyJson = singleBody()
            when (type) {
                EventType.FEEDING -> {
                    assertEquals("formula", bodyJson["type"]!!.jsonPrimitive.content)
                    assertEquals("bottle", bodyJson["method"]!!.jsonPrimitive.content)
                    assertEquals(150.0, bodyJson["amount"]!!.jsonPrimitive.double, 0.0)
                    assertEquals("spit up after", bodyJson["notes"]!!.jsonPrimitive.content)
                }
                EventType.CHANGE -> {
                    assertTrue(bodyJson["wet"]!!.jsonPrimitive.boolean)
                    assertTrue(bodyJson["solid"]!!.jsonPrimitive.boolean)
                    assertEquals("yellow", bodyJson["color"]!!.jsonPrimitive.content)
                    assertEquals(2.5, bodyJson["amount"]!!.jsonPrimitive.double, 0.0)
                }
                EventType.SLEEP -> assertEquals("night stretch", bodyJson["notes"]!!.jsonPrimitive.content)
                EventType.NOTE -> assertEquals("first words!", bodyJson["note"]!!.jsonPrimitive.content)
                EventType.PUMPING -> assertEquals(85.0, bodyJson["amount"]!!.jsonPrimitive.double, 0.0)
                EventType.TUMMY_TIME -> assertEquals("Rolled over!", bodyJson["milestone"]!!.jsonPrimitive.content)
            }
            assertEquals(SyncState.SYNCED.name, db.eventDao().byLocalUuid(created.localUuid)!!.syncState)
        }
        assertFalse(db.outboxDao().snapshot().isNotEmpty())
    }

    // -- Draft builders ----------------------------------------------------------------

    private fun sleepDraft(noteText: String = "nap", startShift: Long = 0) = EventDraft(
        type = EventType.SLEEP,
        childId = 1,
        startMillis = now + startShift,
        endMillis = now + startShift + 45 * 60_000,
        noteText = noteText,
    )

    private fun feedingDraft(
        typeWire: String = "breast milk",
        methodWire: String = "bottle",
        amount: Double? = 120.0,
        noteText: String? = null,
    ) = EventDraft(
        type = EventType.FEEDING,
        childId = 1,
        startMillis = now,
        endMillis = now + 10 * 60_000,
        feedingType = typeWire,
        feedingMethod = methodWire,
        amount = amount,
        noteText = noteText,
    )
}
