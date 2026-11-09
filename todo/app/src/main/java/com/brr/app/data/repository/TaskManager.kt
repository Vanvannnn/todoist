package com.brr.app.data.repository

import com.brr.app.data.db.SubtaskEntity
import com.brr.app.data.db.TaskEntity
import com.brr.app.domain.QuickAddResult
import com.brr.app.domain.model.HistoryType
import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.reminder.ReminderCoordinator

/**
 * Façade utilisée par les ViewModels : toute modification de tâche passe par ici afin que
 * les rappels soient toujours reprogrammés ou annulés de façon cohérente.
 */
class TaskManager(
    private val tasks: TaskRepository,
    private val projects: ProjectRepository,
    private val reminders: ReminderCoordinator,
) {
    data class Draft(
        val id: Long = 0,
        val title: String,
        val description: String = "",
        val dueAt: Long? = null,
        val hasTime: Boolean = false,
        val priority: Priority = Priority.NORMAL,
        val projectId: Long? = null,
        val level: ReminderLevel = ReminderLevel.INSISTANT,
        val remindersEnabled: Boolean = true,
        val repeatType: com.brr.app.domain.model.RepeatType = com.brr.app.domain.model.RepeatType.NONE,
        val repeatInterval: Int = 1,
        val repeatDays: Int = 0,
        val nagIntervalMinutes: Int? = null,
        val customMessage: String? = null,
        val subtasks: List<Pair<String, Boolean>> = emptyList(),
        val offsets: List<Int> = emptyList(),
    )

    suspend fun save(d: Draft): Long {
        require(d.title.isNotBlank()) { "Le titre est obligatoire" }
        val now = System.currentTimeMillis()
        val old = if (d.id != 0L) tasks.get(d.id) else null
        val base = old ?: TaskEntity(title = d.title, createdAt = now, updatedAt = now)
        val scheduleChanged = old == null || old.dueAt != d.dueAt || old.hasTime != d.hasTime ||
            old.remindersEnabled != d.remindersEnabled || old.reminderLevel != d.level
        var entity = base.copy(
            title = d.title.trim(), description = d.description.trim(), dueAt = d.dueAt, hasTime = d.hasTime && d.dueAt != null,
            priority = d.priority, projectId = d.projectId, reminderLevel = d.level, remindersEnabled = d.remindersEnabled,
            repeatType = d.repeatType, repeatInterval = d.repeatInterval.coerceAtLeast(1), repeatDays = d.repeatDays,
            nagIntervalMinutes = d.nagIntervalMinutes?.takeIf { it > 0 }, customMessage = d.customMessage?.trim()?.ifBlank { null },
            updatedAt = now,
        )
        if (scheduleChanged) entity = entity.withReminderState(com.brr.app.domain.ReminderState(), null)
        val id = tasks.save(entity, d.subtasks, d.offsets)
        if (old == null) tasks.log(id, entity.title, HistoryType.CREATED)
        reminders.reschedule(id)
        return id
    }

    suspend fun quickAdd(r: QuickAddResult, defaultLevel: ReminderLevel, fallbackProjectId: Long? = null): Long {
        val projectId = r.projectName?.let { projects.findOrCreate(it) } ?: fallbackProjectId
        return save(Draft(
            title = r.title, dueAt = r.dueAt, hasTime = r.hasTime, priority = r.priority ?: Priority.NORMAL,
            projectId = projectId, level = r.level ?: defaultLevel,
        ))
    }

    suspend fun complete(id: Long): Long? = reminders.complete(id)
    suspend fun reopen(id: Long) = reminders.reopen(id)
    suspend fun snooze(id: Long, minutes: Int) = reminders.snooze(id, minutes)
    suspend fun postponeToTomorrow(id: Long) = reminders.postponeToTomorrow(id)
    suspend fun disableReminders(id: Long) = reminders.disableReminders(id)

    suspend fun enableReminders(id: Long) {
        tasks.setRemindersEnabled(id, true)
        reminders.reschedule(id)
    }

    suspend fun delete(id: Long) {
        val t = tasks.get(id) ?: return
        reminders.cancel(id)
        tasks.delete(id)
        tasks.log(null, t.title, HistoryType.DELETED)
    }

    suspend fun toggleSubtask(s: SubtaskEntity) = tasks.toggleSubtask(s)
    suspend fun addSubtask(taskId: Long, title: String, position: Int) { if (title.isNotBlank()) tasks.addSubtask(taskId, title, position) }
    suspend fun deleteSubtask(s: SubtaskEntity) = tasks.deleteSubtask(s)

    suspend fun deleteProject(id: Long) {
        projects.delete(id)
    }
}
