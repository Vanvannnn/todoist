package com.brr.app.ui.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brr.app.AppContainer
import com.brr.app.data.repository.TaskManager
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.voice.Moment
import com.brr.app.domain.voice.Recurrence
import com.brr.app.domain.voice.SchedulePlanner
import com.brr.app.domain.voice.VoiceParser
import com.brr.app.domain.voice.VoiceTask
import com.brr.app.domain.voice.Window
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

data class VoiceUiState(
    val transcript: String = "",
    val partial: String = "",
    val tasks: List<VoiceTask> = emptyList(),
    val questions: List<String> = emptyList(),
    val isPlanRequest: Boolean = true,
    val plan: SchedulePlanner.Result? = null,
    val today: LocalDate = LocalDate.now(),
    val saving: Boolean = false,
    val savedCount: Int? = null,
)

/** Dictée → tâches → planning → enregistrement dans Bullylist. */
class VoiceViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(VoiceUiState())
    val state = _state.asStateFlow()
    private var events: List<SchedulePlanner.BusyEvent> = emptyList()

    private fun now(): Moment { val t = LocalDateTime.now(); return Moment(t.toLocalDate(), t.hour * 60 + t.minute) }

    fun setTranscript(final: String, partial: String = "") = _state.update { it.copy(transcript = final, partial = partial) }

    fun restart() = _state.update { VoiceUiState() }

    fun analyse() = viewModelScope.launch {
        val s = c.settings.current()
        val text = (_state.value.transcript + " " + _state.value.partial).trim()
        if (text.isEmpty()) return@launch
        val d = VoiceParser.parse(text, now(), s.learnedDurations)
        events = runCatching { c.calendars.busyEvents() }.getOrDefault(emptyList())
        _state.update { it.copy(transcript = text, partial = "", tasks = d.tasks, questions = d.questions, isPlanRequest = d.isPlanRequest, savedCount = null) }
        replan()
    }

    fun replan() = viewModelScope.launch {
        val s = c.settings.current()
        val tasks = _state.value.tasks
        val r = if (tasks.isEmpty()) null
        else SchedulePlanner.plan(tasks, events, now(), SchedulePlanner.Settings(maxFocusPerDay = s.maxFocusMinutes))
        _state.update { it.copy(plan = r, today = now().date) }
    }

    /** Modifications avant validation : la planification est recalculée aussitôt. */
    fun remove(id: String) { _state.update { st -> st.copy(tasks = st.tasks.filter { it.id != id }.map { t -> t.copy(dependsOn = t.dependsOn.filter { it != id }.toMutableList()) }) }; replan() }
    fun changeMinutes(id: String, delta: Int) = edit(id) { t ->
        if (t.recurrence?.type == Recurrence.Type.DAILY) t.copy(sessionMinutes = ((t.sessionMinutes ?: 30) + delta).coerceIn(10, 240), explicitMinutes = (t.sessionMinutes ?: 30) + delta)
        else t.copy(minutes = (t.minutes + delta).coerceIn(5, 600), explicitMinutes = t.minutes + delta)
    }
    fun moveTo(id: String, day: LocalDate?) = edit(id) { t -> t.copy(window = day?.let { Window(it, 480, 1350, false, "") }) }
    fun setLevel(id: String, level: ReminderLevel) = edit(id) { it.copy(level = level) }

    private fun edit(id: String, f: (VoiceTask) -> VoiceTask) {
        _state.update { st -> st.copy(tasks = st.tasks.map { if (it.id == id) f(it.copy(dependsOn = it.dependsOn.toMutableList(), notes = it.notes.toMutableList())) else it }) }
        replan()
    }

    /** « Tout accepter » : chaque créneau devient une tâche dont le rappel sonne au début du bloc. */
    fun accept(onDone: (Int) -> Unit) = viewModelScope.launch {
        val st = _state.value; val plan = st.plan ?: return@launch
        _state.update { it.copy(saving = true) }
        val zone = ZoneId.systemDefault()
        fun epoch(d: LocalDate, minute: Int) = d.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        val drafts = st.tasks.flatMap { t ->
            val blocks = plan.days.flatMap { day -> day.blocks.filter { it.kind == SchedulePlanner.Kind.TASK && it.taskId == t.id }.map { day.date to it } }
            val deadline = t.deadline?.let { epoch(it.date, minOf(1439, it.minute)) }
            if (blocks.isEmpty()) listOf(TaskManager.Draft(title = t.title, dueAt = deadline, hasTime = deadline != null, priority = t.priority, level = t.level, deadlineAt = deadline))
            else blocks.mapIndexed { i, (date, b) ->
                TaskManager.Draft(
                    title = if (blocks.size > 1) "${t.title} (${i + 1}/${blocks.size})" else t.title,
                    description = t.notes.joinToString("\n"),
                    dueAt = epoch(date, b.start), hasTime = true, priority = t.priority, level = t.level,
                    plannedStart = epoch(date, b.start), plannedMinutes = b.end - b.start, deadlineAt = deadline,
                )
            }
        }
        c.taskManager.savePlan(drafts)
        _state.update { it.copy(saving = false, savedCount = drafts.size) }
        onDone(drafts.size)
    }
}
