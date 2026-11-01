package app.dandle.data.db

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Room migrations (brief 05 §3). The database is a cache PLUS the offline queue — PENDING and
 * FAILED rows are the user's only copy of entries captured offline — so v1→v2 is a real
 * table-rebuild migration, never destructive. SQLite cannot ALTER a column type, so each
 * table is rebuilt: create `_new`, copy with CAST, drop, rename.
 */
object Migrations {

    val V1_2 = object : Migration(1, 2) {
        override fun migrate(db: SupportSQLiteDatabase) {
            // children: id Long → String.
            db.execSQL(
                """
                CREATE TABLE children_new (
                    id TEXT NOT NULL PRIMARY KEY,
                    firstName TEXT NOT NULL,
                    lastName TEXT,
                    birthDate TEXT NOT NULL,
                    birthTime TEXT,
                    slug TEXT NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("INSERT INTO children_new (id, firstName, lastName, birthDate, birthTime, slug) " +
                "SELECT CAST(id AS TEXT), firstName, lastName, birthDate, birthTime, slug FROM children")
            db.execSQL("DROP TABLE children")
            db.execSQL("ALTER TABLE children_new RENAME TO children")

            // events: serverId/conflictServerId Long → String, childId Long → String,
            // + `deleted` tombstone (default 0 = live).
            db.execSQL(
                """
                CREATE TABLE events_new (
                    localUuid TEXT NOT NULL PRIMARY KEY,
                    serverId TEXT,
                    type TEXT NOT NULL,
                    childId TEXT NOT NULL,
                    startMillis INTEGER NOT NULL,
                    endMillis INTEGER NOT NULL,
                    feedingType TEXT,
                    feedingMethod TEXT,
                    amount REAL,
                    wet INTEGER,
                    solid INTEGER,
                    color TEXT,
                    noteText TEXT,
                    milestone TEXT,
                    syncState TEXT NOT NULL,
                    failureReason TEXT,
                    conflictUiPath TEXT,
                    conflictServerId TEXT,
                    createdAtMillis INTEGER NOT NULL,
                    updatedAtMillis INTEGER NOT NULL,
                    deleted INTEGER NOT NULL DEFAULT 0
                )
                """.trimIndent()
            )
            db.execSQL(
                "INSERT INTO events_new (localUuid, serverId, type, childId, startMillis, endMillis, " +
                    "feedingType, feedingMethod, amount, wet, solid, color, noteText, milestone, " +
                    "syncState, failureReason, conflictUiPath, conflictServerId, createdAtMillis, " +
                    "updatedAtMillis, deleted) " +
                    "SELECT localUuid, CAST(serverId AS TEXT), type, CAST(childId AS TEXT), startMillis, " +
                    "endMillis, feedingType, feedingMethod, amount, wet, solid, color, noteText, " +
                    "milestone, syncState, failureReason, conflictUiPath, CAST(conflictServerId AS TEXT), " +
                    "createdAtMillis, updatedAtMillis, 0 FROM events"
            )
            db.execSQL("DROP TABLE events")
            db.execSQL("ALTER TABLE events_new RENAME TO events")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_events_serverId ON events(serverId)")
            db.execSQL("CREATE INDEX IF NOT EXISTS index_events_childId_startMillis ON events(childId, startMillis)")

            // timers: childId/serverTimerId Long → String; autoGenerate pk untouched.
            db.execSQL(
                """
                CREATE TABLE timers_new (
                    id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                    kind TEXT NOT NULL,
                    childId TEXT NOT NULL,
                    startedAtMillis INTEGER NOT NULL,
                    serverTimerId TEXT,
                    createdAtMillis INTEGER NOT NULL
                )
                """.trimIndent()
            )
            db.execSQL("INSERT INTO timers_new (id, kind, childId, startedAtMillis, serverTimerId, createdAtMillis) " +
                "SELECT id, kind, CAST(childId AS TEXT), startedAtMillis, CAST(serverTimerId AS TEXT), " +
                "createdAtMillis FROM timers")
            db.execSQL("DROP TABLE timers")
            db.execSQL("ALTER TABLE timers_new RENAME TO timers")
        }
    }

    val ALL: Array<Migration> = arrayOf(V1_2)
}
