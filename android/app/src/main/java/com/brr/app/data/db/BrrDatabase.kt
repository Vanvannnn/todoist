package com.brr.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        TaskEntity::class, ProjectEntity::class, ReminderEntity::class, SubtaskEntity::class,
        HistoryEntity::class, CustomPunchlineEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class BrrDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun reminderDao(): ReminderDao
    abstract fun subtaskDao(): SubtaskDao
    abstract fun projectDao(): ProjectDao
    abstract fun historyDao(): HistoryDao
    abstract fun punchlineDao(): PunchlineDao

    companion object {
        fun build(context: Context): BrrDatabase =
            Room.databaseBuilder(context, BrrDatabase::class.java, "brr.db").build()
    }
}
