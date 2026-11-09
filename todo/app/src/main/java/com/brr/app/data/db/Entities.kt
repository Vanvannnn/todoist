package com.brr.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import com.brr.app.domain.PlannerTask
import com.brr.app.domain.ReminderState
import com.brr.app.domain.model.HistoryType
import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.RepeatType
import com.brr.app.punchline.PunchlineCategory

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val color: Long,
    val createdAt: Long,
)

@Entity(
    tableName = "tasks",
    foreignKeys = [ForeignKey(
        entity = ProjectEntity::class, parentColumns = ["id"], childColumns = ["projectId"],
        onDelete = ForeignKey.SET_NULL,
    )],
    indices = [Index("projectId"), Index("completed"), Index("dueAt")],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val description: String = "",
    val dueAt: Long? = null,
    val hasTime: Boolean = false,
    val priority: Priority = Priority.NORMAL,
    val projectId: Long? = null,
    val reminderLevel: ReminderLevel = ReminderLevel.INSISTANT,
    val remindersEnabled: Boolean = true,
    val repeatType: RepeatType = RepeatType.NONE,
    val repeatInterval: Int = 1,
    val repeatDays: Int = 0,
    val nagIntervalMinutes: Int? = null,
    val customMessage: String? = null,
    val completed: Boolean = false,
    val completedAt: Long? = null,
    val createdAt: Long,
    val updatedAt: Long,
    // État du cycle de rappel
    val snoozedUntil: Long? = null,
    val escalationStep: Int = 0,
    val lastFiredAt: Long? = null,
    val snoozeCount: Int = 0,
    val nextReminderAt: Long? = null,
) {
    val reminderState: ReminderState get() = ReminderState(snoozedUntil, escalationStep, lastFiredAt, snoozeCount)

    fun withReminderState(s: ReminderState, next: Long?): TaskEntity = copy(
        snoozedUntil = s.snoozedUntil, escalationStep = s.escalationStep, lastFiredAt = s.lastFiredAt,
        snoozeCount = s.snoozeCount, nextReminderAt = next,
    )
}

@Entity(
    tableName = "reminders",
    foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    /** Minutes avant l'échéance (rappels additionnels ; l'échéance elle-même sonne toujours). */
    val offsetMinutes: Int,
)

@Entity(
    tableName = "subtasks",
    foreignKeys = [ForeignKey(TaskEntity::class, ["id"], ["taskId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("taskId")],
)
data class SubtaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long,
    val title: String,
    val done: Boolean = false,
    val position: Int = 0,
)

@Entity(tableName = "history", indices = [Index("at")])
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long?,
    val taskTitle: String,
    val type: HistoryType,
    val at: Long,
    val detail: String = "",
)

@Entity(tableName = "custom_punchlines")
data class CustomPunchlineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val text: String,
    val category: PunchlineCategory,
    val intensity: Int,
    val createdAt: Long,
)

data class TaskWithDetails(
    @Embedded val task: TaskEntity,
    @Relation(parentColumn = "projectId", entityColumn = "id") val project: ProjectEntity?,
    @Relation(parentColumn = "id", entityColumn = "taskId") val subtasks: List<SubtaskEntity>,
    @Relation(parentColumn = "id", entityColumn = "taskId") val reminders: List<ReminderEntity>,
) {
    val sortedSubtasks: List<SubtaskEntity> get() = subtasks.sortedBy { it.position }

    fun toPlannerTask(): PlannerTask = PlannerTask(
        dueAt = task.dueAt, hasTime = task.hasTime, completed = task.completed,
        remindersEnabled = task.remindersEnabled, level = task.reminderLevel,
        nagIntervalMinutes = task.nagIntervalMinutes,
        preOffsetsMinutes = reminders.map { it.offsetMinutes }, state = task.reminderState,
    )
}

data class ProjectWithCounts(
    @Embedded val project: ProjectEntity,
    val openCount: Int,
    val doneCount: Int,
)
