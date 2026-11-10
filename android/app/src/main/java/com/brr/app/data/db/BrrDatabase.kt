package com.brr.app.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskEntity::class, ProjectEntity::class, ReminderEntity::class, SubtaskEntity::class,
        HistoryEntity::class, CustomPunchlineEntity::class, CalendarSourceEntity::class, CalendarEventEntity::class,
    ],
    version = 2,
    exportSchema = true,
    // V1 → V2 : colonnes de planning sur les tâches + tables de calendriers. Les données existantes sont conservées.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
abstract class BrrDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun reminderDao(): ReminderDao
    abstract fun subtaskDao(): SubtaskDao
    abstract fun projectDao(): ProjectDao
    abstract fun historyDao(): HistoryDao
    abstract fun punchlineDao(): PunchlineDao
    abstract fun calendarDao(): CalendarDao

    companion object {
        fun build(context: Context): BrrDatabase =
            Room.databaseBuilder(context, BrrDatabase::class.java, "brr.db").build()
    }
}
