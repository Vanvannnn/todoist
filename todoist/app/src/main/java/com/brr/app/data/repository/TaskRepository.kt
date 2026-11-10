package com.brr.app.data.repository

import androidx.room.withTransaction
import com.brr.app.data.db.BrrDatabase
import com.brr.app.data.db.HistoryEntity
import com.brr.app.data.db.ReminderEntity
import com.brr.app.data.db.SubtaskEntity
import com.brr.app.data.db.TaskEntity
import com.brr.app.data.db.TaskWithDetails
import com.brr.app.domain.ReminderState
import com.brr.app.domain.model.HistoryType
import com.brr.app.reminder.ReminderStore
import kotlinx.coroutines.flow.Flow

class TaskRepository(private val db: BrrDatabase) : ReminderStore {
    private val tasks = db.taskDao()
    private val reminders = db.reminderDao()
    private val subtasks = db.subtaskDao()
    private val history = db.historyDao()

    fun observeOpen(): Flow<List<TaskWithDetails>> = tasks.observeOpen()
    fun observeCompleted(): Flow<List<TaskWithDetails>> = tasks.observeCompleted()
    fun observe(id: Long): Flow<TaskWithDetails?> = tasks.observe(id)
    fun observeCompletedSince(since: Long): Flow<Int> = tasks.observeCompletedSince(since)
    fun observeHistory(): Flow<List<HistoryEntity>> = history.observeRecent()
    fun observeHistorySince(since: Long): Flow<List<HistoryEntity>> = history.observeSince(since)
    fun observeTaskHistory(taskId: Long): Flow<List<HistoryEntity>> = history.observeForTask(taskId)

    suspend fun clearHistory() = history.clear()

    /** Crée ou met à jour une tâche avec ses sous-tâches et ses rappels, de façon atomique. */
    suspend fun save(task: TaskEntity, subtaskTitles: List<Pair<String, Boolean>>, offsets: List<Int>): Long =
        db.withTransaction {
            val id = if (task.id == 0L) tasks.insert(task) else { tasks.update(task); task.id }
            subtasks.deleteForTask(id)
            subtasks.insertAll(subtaskTitles.filter { it.first.isNotBlank() }
                .mapIndexed { i, (title, done) -> SubtaskEntity(taskId = id, title = title.trim(), done = done, position = i) })
            reminders.deleteForTask(id)
            reminders.insertAll(offsets.filter { it > 0 }.distinct().map { ReminderEntity(taskId = id, offsetMinutes = it) })
            id
        }

    suspend fun get(id: Long): TaskEntity? = tasks.get(id)

    suspend fun delete(id: Long) = tasks.delete(id)

    suspend fun toggleSubtask(s: SubtaskEntity) = subtasks.update(s.copy(done = !s.done))
    suspend fun addSubtask(taskId: Long, title: String, position: Int) =
        subtasks.insert(SubtaskEntity(taskId = taskId, title = title.trim(), position = position))
    suspend fun deleteSubtask(s: SubtaskEntity) = subtasks.delete(s)

    // --- ReminderStore ---
    override suspend fun task(id: Long): TaskWithDetails? = tasks.getWithDetails(id)
    override suspend fun schedulableTasks(): List<TaskWithDetails> = tasks.getSchedulable()
    override suspend fun staleScheduledIds(): List<Long> = tasks.getUnschedulableIds()

    override suspend fun saveState(taskId: Long, state: ReminderState, next: Long?) =
        tasks.updateReminderState(taskId, state.snoozedUntil, state.escalationStep, state.lastFiredAt, state.snoozeCount, next)

    override suspend fun markCompleted(taskId: Long, at: Long) {
        tasks.get(taskId)?.let { tasks.update(it.copy(completed = true, completedAt = at, updatedAt = at)) }
    }

    override suspend fun markOpen(taskId: Long) {
        tasks.get(taskId)?.let { tasks.update(it.copy(completed = false, completedAt = null, updatedAt = System.currentTimeMillis())) }
    }

    override suspend fun moveDue(taskId: Long, newDue: Long, hasTime: Boolean) {
        tasks.get(taskId)?.let {
            tasks.update(it.copy(dueAt = newDue, hasTime = hasTime, updatedAt = System.currentTimeMillis())
                .withReminderState(ReminderState(), null))
        }
    }

    override suspend fun setRemindersEnabled(taskId: Long, enabled: Boolean) {
        tasks.get(taskId)?.let { tasks.update(it.copy(remindersEnabled = enabled, updatedAt = System.currentTimeMillis())) }
    }

    override suspend fun resetSubtasks(taskId: Long) {
        tasks.getWithDetails(taskId)?.subtasks?.filter { it.done }?.forEach { subtasks.update(it.copy(done = false)) }
    }

    override suspend fun log(taskId: Long?, title: String, type: HistoryType, detail: String) =
        history.insert(HistoryEntity(taskId = taskId, taskTitle = title, type = type, at = System.currentTimeMillis(), detail = detail))
}
