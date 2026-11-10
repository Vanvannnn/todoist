package com.brr.app.ui.screens.edit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brr.app.AppContainer
import com.brr.app.data.db.ProjectEntity
import com.brr.app.data.repository.TaskManager
import com.brr.app.domain.QuickAddParser
import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.RepeatType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

data class SubtaskDraft(val key: Long, val title: String, val done: Boolean = false)

data class EditState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val title: String = "",
    val description: String = "",
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val priority: Priority = Priority.NORMAL,
    val projectId: Long? = null,
    val level: ReminderLevel = ReminderLevel.INSISTANT,
    val remindersEnabled: Boolean = true,
    val repeatType: RepeatType = RepeatType.NONE,
    val repeatInterval: Int = 1,
    val repeatDays: Int = 0,
    val nagInterval: Int? = null,
    val customMessage: String = "",
    val subtasks: List<SubtaskDraft> = emptyList(),
    val offsets: Set<Int> = emptySet(),
    val saving: Boolean = false,
    val error: String? = null,
    val savedId: Long? = null,
)

class TaskEditViewModel(
    private val c: AppContainer,
    private val taskId: Long,
    presetProjectId: Long?,
    presetText: String?,
) : ViewModel() {
    private val _state = MutableStateFlow(EditState())
    val state: StateFlow<EditState> = _state.asStateFlow()
    val projects: StateFlow<List<ProjectEntity>> = c.projects.observeAll().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var keySeq = 1L
    private val zone = ZoneId.systemDefault()

    init {
        viewModelScope.launch {
            val settings = c.settings.current()
            if (taskId > 0) {
                val t = c.tasks.task(taskId)
                if (t == null) { _state.update { it.copy(loading = false, error = "Cette tâche n'existe plus.") }; return@launch }
                val e = t.task
                val zdt = e.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
                _state.value = EditState(
                    loading = false, isNew = false, title = e.title, description = e.description,
                    date = zdt?.toLocalDate(), time = if (e.hasTime) zdt?.toLocalTime()?.withSecond(0)?.withNano(0) else null,
                    priority = e.priority, projectId = e.projectId, level = e.reminderLevel, remindersEnabled = e.remindersEnabled,
                    repeatType = e.repeatType, repeatInterval = e.repeatInterval, repeatDays = e.repeatDays,
                    nagInterval = e.nagIntervalMinutes, customMessage = e.customMessage ?: "",
                    subtasks = t.sortedSubtasks.map { SubtaskDraft(keySeq++, it.title, it.done) },
                    offsets = t.reminders.map { it.offsetMinutes }.toSet(),
                )
            } else {
                var s = EditState(loading = false, level = settings.defaultLevel, projectId = presetProjectId)
                if (!presetText.isNullOrBlank()) {
                    val r = QuickAddParser.parse(presetText, System.currentTimeMillis(), zone)
                    val zdt = r.dueAt?.let { Instant.ofEpochMilli(it).atZone(zone) }
                    val pid = r.projectName?.let { name -> c.projects.findOrCreate(name) }
                    s = s.copy(
                        title = r.title, date = zdt?.toLocalDate(), time = if (r.hasTime) zdt?.toLocalTime() else null,
                        priority = r.priority ?: s.priority, level = r.level ?: s.level, projectId = pid ?: s.projectId,
                    )
                }
                _state.value = s
            }
        }
    }

    fun update(f: (EditState) -> EditState) = _state.update { f(it).copy(error = null) }

    fun addSubtask(title: String) {
        if (title.isBlank()) return
        update { it.copy(subtasks = it.subtasks + SubtaskDraft(keySeq++, title.trim())) }
    }

    fun save() {
        val s = _state.value
        if (s.title.isBlank()) { _state.update { it.copy(error = "Une tâche sans titre ? Même B.R.R. ne peut pas te rappeler « rien ».") }; return }
        if (s.repeatType != RepeatType.NONE && s.date == null) { _state.update { it.copy(error = "Une tâche répétitive a besoin d'une date de départ.") }; return }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            try {
                val dueAt = s.date?.let { d ->
                    (if (s.time != null) d.atTime(s.time).atZone(zone) else d.atStartOfDay(zone)).toInstant().toEpochMilli()
                }
                val id = c.taskManager.save(TaskManager.Draft(
                    id = if (taskId > 0) taskId else 0, title = s.title, description = s.description, dueAt = dueAt,
                    hasTime = s.time != null, priority = s.priority, projectId = s.projectId, level = s.level,
                    remindersEnabled = s.remindersEnabled, repeatType = s.repeatType, repeatInterval = s.repeatInterval,
                    repeatDays = if (s.repeatType == RepeatType.WEEKLY) s.repeatDays else 0, nagIntervalMinutes = s.nagInterval,
                    customMessage = s.customMessage, subtasks = s.subtasks.map { it.title to it.done },
                    offsets = if (dueAt != null) s.offsets.toList() else emptyList(),
                ))
                _state.update { it.copy(saving = false, savedId = id) }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = "Enregistrement impossible : ${e.message}") }
            }
        }
    }
}
