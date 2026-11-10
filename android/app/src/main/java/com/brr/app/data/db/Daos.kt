package com.brr.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Transaction
    @Query("SELECT * FROM tasks WHERE completed = 0 ORDER BY dueAt IS NULL, dueAt, priority DESC, createdAt")
    fun observeOpen(): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE completed = 1 ORDER BY completedAt DESC LIMIT :limit")
    fun observeCompleted(limit: Int = 300): Flow<List<TaskWithDetails>>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    fun observe(id: Long): Flow<TaskWithDetails?>

    @Transaction
    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun getWithDetails(id: Long): TaskWithDetails?

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Transaction
    @Query("SELECT * FROM tasks WHERE completed = 0 AND (dueAt IS NOT NULL OR snoozedUntil IS NOT NULL)")
    suspend fun getSchedulable(): List<TaskWithDetails>

    @Query("SELECT id FROM tasks WHERE completed = 1 OR remindersEnabled = 0")
    suspend fun getUnschedulableIds(): List<Long>

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE tasks SET snoozedUntil = :snoozedUntil, escalationStep = :step, lastFiredAt = :lastFiredAt, snoozeCount = :snoozeCount, nextReminderAt = :next WHERE id = :id")
    suspend fun updateReminderState(id: Long, snoozedUntil: Long?, step: Int, lastFiredAt: Long?, snoozeCount: Int, next: Long?)

    @Query("SELECT COUNT(*) FROM tasks WHERE completed = 1 AND completedAt >= :since")
    fun observeCompletedSince(since: Long): Flow<Int>
}

@Dao
interface ReminderDao {
    @Insert
    suspend fun insertAll(items: List<ReminderEntity>)

    @Query("DELETE FROM reminders WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)
}

@Dao
interface SubtaskDao {
    @Insert
    suspend fun insert(item: SubtaskEntity): Long

    @Insert
    suspend fun insertAll(items: List<SubtaskEntity>)

    @Update
    suspend fun update(item: SubtaskEntity)

    @Delete
    suspend fun delete(item: SubtaskEntity)

    @Query("DELETE FROM subtasks WHERE taskId = :taskId")
    suspend fun deleteForTask(taskId: Long)
}

@Dao
interface ProjectDao {
    @Query(
        """SELECT p.*, 
           (SELECT COUNT(*) FROM tasks t WHERE t.projectId = p.id AND t.completed = 0) AS openCount,
           (SELECT COUNT(*) FROM tasks t WHERE t.projectId = p.id AND t.completed = 1) AS doneCount
           FROM projects p ORDER BY p.name COLLATE NOCASE"""
    )
    fun observeWithCounts(): Flow<List<ProjectWithCounts>>

    @Query("SELECT * FROM projects ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    fun observe(id: Long): Flow<ProjectEntity?>

    @Query("SELECT * FROM projects WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): ProjectEntity?

    @Insert
    suspend fun insert(p: ProjectEntity): Long

    @Update
    suspend fun update(p: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(e: HistoryEntity)

    @Query("SELECT * FROM history ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 500): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE taskId = :taskId ORDER BY at DESC LIMIT 50")
    fun observeForTask(taskId: Long): Flow<List<HistoryEntity>>

    @Query("SELECT * FROM history WHERE at >= :since ORDER BY at DESC")
    fun observeSince(since: Long): Flow<List<HistoryEntity>>

    @Query("DELETE FROM history")
    suspend fun clear()
}

@Dao
interface PunchlineDao {
    @Query("SELECT * FROM custom_punchlines ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<CustomPunchlineEntity>>

    @Query("SELECT * FROM custom_punchlines")
    suspend fun getAll(): List<CustomPunchlineEntity>

    @Insert
    suspend fun insert(p: CustomPunchlineEntity): Long

    @Delete
    suspend fun delete(p: CustomPunchlineEntity)
}

@Dao
interface CalendarDao {
    @Query("SELECT * FROM calendar_sources ORDER BY id")
    fun observeSources(): Flow<List<CalendarSourceEntity>>

    @Query("SELECT * FROM calendar_sources ORDER BY id")
    suspend fun sources(): List<CalendarSourceEntity>

    @Query("SELECT * FROM calendar_sources WHERE id = :id")
    suspend fun source(id: Long): CalendarSourceEntity?

    @Insert
    suspend fun insertSource(s: CalendarSourceEntity): Long

    @Update
    suspend fun updateSource(s: CalendarSourceEntity)

    @Query("DELETE FROM calendar_sources WHERE id = :id")
    suspend fun deleteSource(id: Long)

    @Query("DELETE FROM calendar_events WHERE sourceId = :sourceId")
    suspend fun clearEvents(sourceId: Long)

    @Insert
    suspend fun insertEvents(items: List<CalendarEventEntity>)

    /** Événements des sources actives qui chevauchent [from, to[. */
    @Query("SELECT e.* FROM calendar_events e JOIN calendar_sources s ON s.id = e.sourceId WHERE s.enabled = 1 AND e.`end` > :from AND e.start < :to ORDER BY e.start")
    suspend fun eventsBetween(from: Long, to: Long): List<CalendarEventEntity>

    @Query("SELECT e.* FROM calendar_events e JOIN calendar_sources s ON s.id = e.sourceId WHERE s.enabled = 1 AND e.`end` > :from ORDER BY e.start LIMIT :limit")
    fun observeUpcoming(from: Long, limit: Int = 30): Flow<List<CalendarEventEntity>>
}
