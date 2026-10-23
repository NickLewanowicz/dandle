package app.dandle.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [ChildEntity::class, EventEntity::class, TimerEntity::class, OutboxOpEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class BabyBuddyDatabase : RoomDatabase() {
    abstract fun childDao(): ChildDao
    abstract fun eventDao(): EventDao
    abstract fun timerDao(): TimerDao
    abstract fun outboxDao(): OutboxDao

    companion object {
        const val NAME = "babybuddy-next.db"
    }
}
