package app.dandle.data.db

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import app.dandle.domain.model.SyncState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * v1→v2 migration contract (brief 05 §3): identity Long → String plus the `deleted` tombstone.
 * The database is a cache PLUS the offline queue, so the migration must round-trip a PENDING
 * row (the user's only copy of an offline capture) byte-faithfully. Builds a REAL v1 database
 * file with raw SQLite, then opens it through Room + [Migrations.V1_2].
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class MigrationTest {

    private lateinit var context: Context
    private val dbName = "migration-test.db"

    /** Exact v1 DDL (the version-1 entity shapes, pre-brief-05). */
    private val v1Callback = object : SupportSQLiteOpenHelper.Callback(1) {
        override fun onCreate(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS children (id INTEGER NOT NULL PRIMARY KEY, " +
                    "firstName TEXT NOT NULL, lastName TEXT, birthDate TEXT NOT NULL, " +
                    "birthTime TEXT, slug TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS events (localUuid TEXT NOT NULL PRIMARY KEY, " +
                    "serverId INTEGER, type TEXT NOT NULL, childId INTEGER NOT NULL, " +
                    "startMillis INTEGER NOT NULL, endMillis INTEGER NOT NULL, feedingType TEXT, " +
                    "feedingMethod TEXT, amount REAL, wet INTEGER, solid INTEGER, color TEXT, " +
                    "noteText TEXT, milestone TEXT, syncState TEXT NOT NULL, failureReason TEXT, " +
                    "conflictUiPath TEXT, conflictServerId INTEGER, createdAtMillis INTEGER NOT NULL, " +
                    "updatedAtMillis INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_events_serverId ON events(serverId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_events_childId_startMillis ON events(childId, startMillis)")
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS timers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "kind TEXT NOT NULL, childId INTEGER NOT NULL, startedAtMillis INTEGER NOT NULL, " +
                    "serverTimerId INTEGER, createdAtMillis INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS outbox (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "opType TEXT NOT NULL, entityType TEXT NOT NULL, localUuid TEXT NOT NULL, " +
                    "payload TEXT NOT NULL, attempts INTEGER NOT NULL, lastError TEXT, " +
                    "createdAtMillis INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS index_outbox_localUuid ON outbox(localUuid)")
        }

        override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(dbName)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(dbName)
    }

    /** Seeds v1 rows and closes the raw handle — simulates an app upgrade mid-queue. */
    private fun seedV1() {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(dbName)
                .callback(v1Callback)
                .build()
        )
        val db = helper.writableDatabase
        db.execSQL(
            "INSERT INTO children (id, firstName, lastName, birthDate, birthTime, slug) " +
                "VALUES (1, 'Milo', NULL, '2026-01-14', '03:20:00', 'milo')"
        )
        // A SYNCED server row (serverId set)…
        db.execSQL(
            "INSERT INTO events (localUuid, serverId, type, childId, startMillis, endMillis, " +
                "syncState, createdAtMillis, updatedAtMillis) " +
                "VALUES ('server:SLEEP:5', 5, 'SLEEP', 1, 1000, 2000, 'SYNCED', 0, 0)"
        )
        // …and a PENDING offline capture (never synced — the row that must not be lost).
        db.execSQL(
            "INSERT INTO events (localUuid, serverId, type, childId, startMillis, endMillis, " +
                "wet, solid, syncState, failureReason, createdAtMillis, updatedAtMillis) " +
                "VALUES ('local-uuid-1', NULL, 'CHANGE', 1, 3000, 3000, 1, 0, 'PENDING', NULL, 77, 88)"
        )
        db.execSQL(
            "INSERT INTO timers (kind, childId, startedAtMillis, serverTimerId, createdAtMillis) " +
                "VALUES ('SLEEP', 1, 4000, 9, 66)"
        )
        db.execSQL(
            "INSERT INTO outbox (opType, entityType, localUuid, payload, attempts, createdAtMillis) " +
                "VALUES ('CREATE', 'CHANGE', 'local-uuid-1', '{}', 0, 77)"
        )
        db.close()
    }

    private fun openRoom(): BabyBuddyDatabase =
        Room.databaseBuilder(context, BabyBuddyDatabase::class.java, dbName)
            .addMigrations(*Migrations.ALL)
            .build()

    @Test
    fun `v1 data round-trips through the String-identity migration`() = runTest {
        seedV1()
        val db = openRoom()

        val child = db.childDao().observeAll().first().single()
        assertEquals("1", child.id)
        assertEquals("Milo", child.firstName)

        val pending = db.eventDao().byLocalUuid("local-uuid-1")
        assertNotNull(pending)
        pending!!.let {
            assertEquals(SyncState.PENDING, SyncState.valueOf(it.syncState))
            assertEquals("1", it.childId)
            assertNull(it.serverId)
            assertEquals(88L, it.updatedAtMillis)
            assertFalse(it.deleted) // tombstone defaults to live
        }

        // Server rows keep their identity — the deterministic merge key must resolve.
        val synced = db.eventDao().byServerId("SLEEP", "5")
        assertNotNull(synced)
        assertEquals("server:SLEEP:5", synced!!.localUuid)
        assertEquals("5", synced.serverId)

        val timer = db.timerDao().snapshot().single()
        assertEquals("1", timer.childId)
        assertEquals("9", timer.serverTimerId)

        // The queued op survives untouched (its payload is backend-neutral in v1 form).
        val op = db.outboxDao().snapshot().single()
        assertEquals("local-uuid-1", op.localUuid)

        db.close()
    }

    @Test
    fun `fresh v2 creation works and tombstone filters reads`() = runTest {
        val db = openRoom()
        db.childDao().upsertAll(
            listOf(
                ChildEntity(id = "abc", firstName = "Zoe", lastName = null, birthDate = "2026-03-01", birthTime = null, slug = "zoe"),
            )
        )
        db.eventDao().upsert(
            EventEntity(
                localUuid = "e1", serverId = null, type = "NOTE", childId = "abc",
                startMillis = 1, endMillis = 1, feedingType = null, feedingMethod = null,
                amount = null, wet = null, solid = null, color = null, noteText = "hi",
                milestone = null, syncState = SyncState.SYNCED.name, failureReason = null,
                conflictUiPath = null, conflictServerId = null, createdAtMillis = 0, updatedAtMillis = 0,
            )
        )
        assertTrue(db.eventDao().observeTimeline("abc").first().isNotEmpty())
        // Tombstoned rows vanish from every read…
        db.eventDao().delete("e1")
        assertTrue(db.eventDao().observeTimeline("abc").first().isEmpty())
        db.close()
    }
}
