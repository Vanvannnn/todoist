package com.brr.app.ui.screens.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brr.app.AppContainer
import com.brr.app.data.ai.AssistantClient
import com.brr.app.data.repository.TaskManager
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.voice.Constraint
import com.brr.app.domain.voice.Moment
import com.brr.app.domain.voice.Recurrence
import com.brr.app.domain.voice.SchedulePlanner
import com.brr.app.domain.voice.Understanding
import com.brr.app.domain.voice.UnderstandingCodec
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

/** Moteur qui a produit la compréhension affichée. */
enum class Engine { AI, LOCAL, LOCAL_FALLBACK }

data class VoiceUiState(
    val transcript: String = "",
    val partial: String = "",
    /** Dictées déjà analysées dans cette conversation. */
    val turns: List<String> = emptyList(),
    val tasks: List<VoiceTask> = emptyList(),
    val constraints: List<Constraint> = emptyList(),
    val questions: List<String> = emptyList(),
    val summary: String = "",
    /** Corrections apportées à la réponse de l'IA (jour de semaine recalculé, doublon fusionné…). */
    val issues: List<String> = emptyList(),
    val engine: Engine? = null,
    /** Pourquoi le moteur local a pris le relais (réseau, serveur, quota…). */
    val notice: String? = null,
    val analysing: Boolean = false,
    val isPlanRequest: Boolean = true,
    val plan: SchedulePlanner.Result? = null,
    val today: LocalDate = LocalDate.now(),
    val saving: Boolean = false,
    val savedCount: Int? = null,
)

/** Conversation vocale → compréhension (IA ou locale) → planning → enregistrement dans Bullylist. */
class VoiceViewModel(private val c: AppContainer) : ViewModel() {
    private val _state = MutableStateFlow(VoiceUiState())
    val state = _state.asStateFlow()
    private var events: List<SchedulePlanner.BusyEvent> = emptyList()
    /** Compréhension IA : référence de la conversation (renvoyée au serveur à chaque tour, retouches comprises). */
    private var understanding: Understanding? = null
    /** Dictées que le serveur n'a pas reçues (repli local) : envoyées avec la suivante. */
    private var unsent: List<String> = emptyList()
    private val levelOverrides = mutableMapOf<String, ReminderLevel>()

    private fun now(): Moment { val t = LocalDateTime.now(); return Moment(t.toLocalDate(), t.hour * 60 + t.minute) }

    fun setTranscript(final: String, partial: String = "") = _state.update { it.copy(transcript = final, partial = partial) }

    fun restart() {
        understanding = null; unsent = emptyList(); levelOverrides.clear()
        _state.update { VoiceUiState() }
    }

    /** Analyse la dictée en cours comme un nouveau tour de la conversation (ajout, précision, correction). */
    fun analyse() = viewModelScope.launch {
        val st = _state.value
        val text = (st.transcript + " " + st.partial).trim()
        if (text.isEmpty() || st.analysing) return@launch
        val s = c.settings.current()
        val n = now()
        val turns = st.turns + text
        _state.update { it.copy(analysing = true, transcript = "", partial = "", savedCount = null) }
        events = runCatching { c.calendars.busyEvents() }.getOrDefault(emptyList())

        var notice: String? = null
        if (s.aiReady) {
            val toSend = (unsent + text).joinToString(" ")
            when (val r = AssistantClient(s.aiServerUrl, s.aiToken).understand(toSend, n, understanding)) {
                is AssistantClient.Outcome.Ok -> {
                    understanding = r.understanding; unsent = emptyList()
                    val p = UnderstandingCodec.toPlanInput(r.understanding, n, s.learnedDurations)
                    _state.update { it.copy(turns = turns, tasks = withOverrides(p.tasks), constraints = p.constraints, questions = p.questions,
                        summary = p.summary, issues = r.issues, engine = Engine.AI, notice = null, analysing = false, isPlanRequest = true) }
                    replan(); return@launch
                }
                is AssistantClient.Outcome.Failed -> { notice = r.message; unsent = unsent + text }
            }
        }
        // Moteur local : toute la conversation est relue d'un bloc (le contexte est conservé).
        val d = VoiceParser.parse(turns.joinToString(" "), n, s.learnedDurations)
        _state.update {
            it.copy(turns = turns, tasks = d.tasks, constraints = d.constraints, questions = d.questions, isPlanRequest = d.isPlanRequest,
                summary = "", issues = emptyList(), engine = if (notice != null) Engine.LOCAL_FALLBACK else Engine.LOCAL, notice = notice, analysing = false)
        }
        replan()
    }

    fun replan() = viewModelScope.launch {
        val s = c.settings.current()
        val st = _state.value
        val busy = events + st.constraints.map { SchedulePlanner.BusyEvent(it.date, it.start, it.end, "Indisponible · ${it.label}") }
        val r = if (st.tasks.isEmpty()) null
        else SchedulePlanner.plan(st.tasks, busy, now(), SchedulePlanner.Settings(maxFocusPerDay = s.maxFocusMinutes))
        _state.update { it.copy(plan = r, today = now().date) }
    }

    private fun withOverrides(tasks: List<VoiceTask>) = tasks.onEach { t -> levelOverrides[t.id]?.let { t.level = it } }

    /** En mode IA, les retouches modifient la compréhension : la suite de la conversation les conserve. */
    private fun editAi(id: String, e: UnderstandingCodec.Edit): Boolean {
        val u = understanding ?: return false
        if (_state.value.engine != Engine.AI) return false
        val nu = UnderstandingCodec.applyEdit(u, id, e)
        understanding = nu
        viewModelScope.launch {
            val s = c.settings.current()
            val p = UnderstandingCodec.toPlanInput(nu, now(), s.learnedDurations)
            _state.update { it.copy(tasks = withOverrides(p.tasks), constraints = p.constraints) }
            replan()
        }
        return true
    }

    /** Modifications avant validation : la planification est recalculée aussitôt. */
    fun remove(id: String) {
        if (editAi(id, UnderstandingCodec.Edit.Remove)) return
        _state.update { st -> st.copy(tasks = st.tasks.filter { it.id != id }.map { t -> t.copy(dependsOn = t.dependsOn.filter { it != id }.toMutableList()) }) }; replan()
    }
    fun changeMinutes(id: String, delta: Int) {
        val t = _state.value.tasks.firstOrNull { it.id == id } ?: return
        val many = t.recurrence?.type == Recurrence.Type.DAILY || t.recurrence?.type == Recurrence.Type.DAYS
        if (editAi(id, UnderstandingCodec.Edit.Minutes(((if (many) t.sessionMinutes ?: 30 else t.minutes) + delta)))) return
        edit(id) { x ->
            if (many) x.copy(sessionMinutes = ((x.sessionMinutes ?: 30) + delta).coerceIn(10, 240), explicitMinutes = (x.sessionMinutes ?: 30) + delta)
            else x.copy(minutes = (x.minutes + delta).coerceIn(5, 600), explicitMinutes = x.minutes + delta)
        }
    }
    fun moveTo(id: String, day: LocalDate?) {
        if (editAi(id, UnderstandingCodec.Edit.Day(day))) return
        edit(id) { t -> t.copy(window = day?.let { Window(it, 480, 1350, false, "") }) }
    }
    fun setLevel(id: String, level: ReminderLevel) {
        levelOverrides[id] = level
        edit(id) { it.copy(level = level) }
    }

    private fun edit(id: String, f: (VoiceTask) -> VoiceTask) {
        _state.update { st -> st.copy(tasks = st.tasks.map { if (it.id == id) f(it.copy(dependsOn = it.dependsOn.toMutableList(), notes = it.notes.toMutableList())) else it }) }
        replan()
    }

    /**
     * « Tout accepter » : chaque créneau devient une tâche Bullylist ordinaire (modifiable, rappels, projet, terminable),
     * dont le rappel sonne au début du bloc. Les séances d'une tâche composée sont rangées dans un même projet.
     */
    fun accept(onDone: (Int) -> Unit) = viewModelScope.launch {
        val st = _state.value; val plan = st.plan ?: return@launch
        _state.update { it.copy(saving = true) }
        val zone = ZoneId.systemDefault()
        fun epoch(d: LocalDate, minute: Int) = d.atStartOfDay(zone).plusMinutes(minute.toLong()).toInstant().toEpochMilli()
        val projectIds = st.tasks.mapNotNull { it.group }.distinct().associateWith { c.projects.findOrCreate(it) }
        val drafts = st.tasks.flatMap { t ->
            val blocks = plan.days.flatMap { day -> day.blocks.filter { it.kind == SchedulePlanner.Kind.TASK && it.taskId == t.id }.map { day.date to it } }
            val deadline = t.deadline?.let { epoch(it.date, minOf(1439, it.minute)) }
            val projectId = t.group?.let { projectIds[it] }
            val subtasks = t.subtasks.map { it to false }
            if (blocks.isEmpty()) listOf(TaskManager.Draft(title = t.title, description = t.notes.joinToString("\n"), dueAt = deadline, hasTime = deadline != null,
                priority = t.priority, level = t.level, deadlineAt = deadline, projectId = projectId, subtasks = subtasks))
            else blocks.mapIndexed { i, (date, b) ->
                TaskManager.Draft(
                    title = if (blocks.size > 1) "${t.title} (${i + 1}/${blocks.size})" else t.title,
                    description = t.notes.joinToString("\n"),
                    dueAt = epoch(date, b.start), hasTime = true, priority = t.priority, level = t.level,
                    plannedStart = epoch(date, b.start), plannedMinutes = b.end - b.start, deadlineAt = deadline,
                    projectId = projectId, subtasks = if (i == 0) subtasks else emptyList(),
                )
            }
        }
        c.taskManager.savePlan(drafts)
        understanding = null; unsent = emptyList(); levelOverrides.clear()
        _state.update { it.copy(saving = false, savedCount = drafts.size) }
        onDone(drafts.size)
    }
}
