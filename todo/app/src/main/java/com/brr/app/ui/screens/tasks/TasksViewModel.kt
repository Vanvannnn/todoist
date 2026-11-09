package com.brr.app.ui.screens.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brr.app.AppContainer
import com.brr.app.data.db.ProjectEntity
import com.brr.app.data.db.TaskWithDetails
import com.brr.app.domain.Bucket
import com.brr.app.domain.QuickAddParser
import com.brr.app.domain.TaskBuckets
import com.brr.app.domain.TaskView
import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.ZoneId

data class TaskFilters(
    val priorities: Set<Priority> = emptySet(),
    val levels: Set<ReminderLevel> = emptySet(),
    val projectIds: Set<Long> = emptySet(),
    val onlyWithDate: Boolean = false,
) {
    val active get() = priorities.isNotEmpty() || levels.isNotEmpty() || projectIds.isNotEmpty() || onlyWithDate
}

data class TaskSection(val bucket: Bucket, val items: List<TaskWithDetails>)

data class TasksUiState(
    val loading: Boolean = true,
    val sections: List<TaskSection> = emptyList(),
    val counts: Map<TaskView, Int> = emptyMap(),
    val projects: List<ProjectEntity> = emptyList(),
    val project: ProjectEntity? = null,
    val now: Long = System.currentTimeMillis(),
) {
    val isEmpty get() = sections.all { it.items.isEmpty() }
}

class TasksViewModel(private val c: AppContainer, private val projectId: Long?) : ViewModel() {
    val view = MutableStateFlow(if (projectId != null) TaskView.ALL else TaskView.TODAY)
    val query = MutableStateFlow("")
    val filters = MutableStateFlow(TaskFilters())

    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(30_000) } }

    val state: StateFlow<TasksUiState> = combine(
        c.tasks.observeOpen(), c.projects.observeAll(), combine(view, query, filters) { v, q, f -> Triple(v, q, f) }, ticker,
    ) { tasks, projects, (v, q, f), now ->
        val zone = ZoneId.systemDefault()
        val scoped = if (projectId != null) tasks.filter { it.task.projectId == projectId } else tasks
        val withBucket = scoped.map { it to TaskBuckets.bucketOf(it.task.dueAt, it.task.hasTime, now, zone) }
        val counts = TaskView.entries.associateWith { tv -> withBucket.count { TaskBuckets.inView(tv, it.second) } }
        val nq = QuickAddParser.normalize(q.trim())
        val filtered = withBucket.filter { (t, b) ->
            (nq.isNotEmpty() || TaskBuckets.inView(v, b)) &&
                (nq.isEmpty() || QuickAddParser.normalize(t.task.title + " " + t.task.description + " " + (t.project?.name ?: "")).contains(nq)) &&
                (f.priorities.isEmpty() || t.task.priority in f.priorities) &&
                (f.levels.isEmpty() || t.task.reminderLevel in f.levels) &&
                (f.projectIds.isEmpty() || t.task.projectId in f.projectIds) &&
                (!f.onlyWithDate || t.task.dueAt != null)
        }
        val sections = Bucket.entries.map { b ->
            TaskSection(b, filtered.filter { it.second == b }.map { it.first }
                .sortedWith(compareBy<TaskWithDetails>({ it.task.dueAt ?: Long.MAX_VALUE }, { -it.task.priority.weight }, { it.task.createdAt })))
        }.filter { it.items.isNotEmpty() }
        TasksUiState(false, sections, counts, projects, projects.firstOrNull { it.id == projectId }, now)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TasksUiState())

    fun complete(id: Long) = viewModelScope.launch { c.taskManager.complete(id) }
    fun reopen(id: Long) = viewModelScope.launch { c.taskManager.reopen(id) }
    fun postpone(id: Long) = viewModelScope.launch { c.taskManager.postponeToTomorrow(id) }

    fun quickAdd(text: String) = viewModelScope.launch {
        if (text.isBlank()) return@launch
        val r = QuickAddParser.parse(text, System.currentTimeMillis(), ZoneId.systemDefault())
        val s = c.settings.current()
        // Dans la vue « Aujourd'hui », une tâche sans date est datée d'aujourd'hui (comme Todoist).
        val dated = if (r.dueAt == null && projectId == null && view.value == TaskView.TODAY)
            r.copy(dueAt = java.time.LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        else if (r.dueAt == null && projectId == null && view.value == TaskView.TOMORROW)
            r.copy(dueAt = java.time.LocalDate.now().plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli())
        else r
        c.taskManager.quickAdd(dated, s.defaultLevel, projectId)
    }
}
