package com.brr.app.domain.voice

import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Compréhension structurée renvoyée par le moteur IA (serveur Bullylist), port de web/js/core/understanding.js.
 * Le serveur la valide déjà ; l'application la relit défensivement (réponse réseau) puis la convertit en tâches
 * du planificateur. Elle reste la référence de la conversation : les retouches de l'utilisateur la modifient et
 * elle est renvoyée telle quelle au tour suivant.
 */
data class DayRef(val date: LocalDate, val said: String)
data class AiDeadline(val date: LocalDate, val time: String?, val said: String)
data class AiSubtask(
    val id: String, val title: String, val plannedDay: DayRef?, val durationMinutes: Int?, val durationSource: String,
    val dependsOn: List<String>, val confidence: String,
)
data class AiTask(
    val id: String, val title: String, val description: String, val plannedDay: DayRef?, val partOfDay: String?, val startTime: String?,
    val deadline: AiDeadline?, val durationMinutes: Int?, val durationSource: String, val priority: String, val sessionDays: List<LocalDate>,
    val sessionMinutes: Int?, val subtasks: List<AiSubtask>, val dependsOn: List<String>, val project: String?, val sourceQuote: String,
    val confidence: String,
)
data class AiConstraint(val date: LocalDate, val startTime: String, val endTime: String, val reason: String, val said: String)
data class Understanding(val tasks: List<AiTask>, val constraints: List<AiConstraint>, val questions: List<String>, val summary: String)

/** Entrée du planificateur issue d'une compréhension : tâches (avec projet et sous-tâches) et indisponibilités. */
data class PlanInput(val tasks: List<VoiceTask>, val constraints: List<Constraint>, val questions: List<String>, val summary: String)

object UnderstandingCodec {
    private const val MAX_MINUTES = 720

    fun toMinute(s: String?): Int? {
        val m = s?.let { Regex("^(\\d{1,2}):(\\d{2})$").find(it) } ?: return null
        val h = m.groupValues[1].toInt(); val mi = m.groupValues[2].toInt()
        return if (h < 24 && mi < 60) h * 60 + mi else null
    }
    private fun date(s: String?): LocalDate? = s?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    private fun JSONObject.str(k: String, max: Int) = if (isNull(k)) "" else optString(k).replace(Regex("\\s+"), " ").trim().take(max)
    private fun JSONObject.strOrNull(k: String, max: Int) = str(k, max).ifEmpty { null }
    private fun JSONObject.minutes(k: String) = if (isNull(k)) null else optInt(k, -1).takeIf { it in 5..MAX_MINUTES }
    private fun JSONObject.ids(k: String) = optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { a.optString(it).takeIf(String::isNotBlank)?.take(24) } } ?: emptyList()
    private fun JSONObject.dayRef(k: String, today: LocalDate): DayRef? {
        val o = optJSONObject(k) ?: return null
        val d = date(o.optString("date")) ?: return null
        return if (d < today) null else DayRef(d, o.str("said", 80))
    }
    private fun oneOf(v: String, allowed: Set<String>, default: String) = if (v in allowed) v else default

    /** Lecture défensive : tout champ inattendu est ignoré ; une structure inutilisable renvoie null. */
    fun parse(o: JSONObject?, today: LocalDate): Understanding? {
        val arr = o?.optJSONArray("tasks") ?: return null
        val tasks = (0 until minOf(arr.length(), 40)).mapNotNull { i ->
            val t = arr.optJSONObject(i) ?: return@mapNotNull null
            val title = t.str("title", 120).ifEmpty { return@mapNotNull null }
            val subs = t.optJSONArray("subtasks")?.let { sa ->
                (0 until minOf(sa.length(), 20)).mapNotNull { j ->
                    val s = sa.optJSONObject(j) ?: return@mapNotNull null
                    val st = s.str("title", 120).ifEmpty { return@mapNotNull null }
                    AiSubtask(s.str("id", 24).ifEmpty { "${t.str("id", 24)}.${j + 1}" }, st, s.dayRef("planned_day", today), s.minutes("duration_minutes"),
                        oneOf(s.str("duration_source", 12), setOf("said", "estimated", "unknown"), "unknown"), s.ids("depends_on"),
                        oneOf(s.str("confidence", 8), setOf("high", "medium", "low"), "medium"))
                }
            } ?: emptyList()
            val dl = t.optJSONObject("deadline")?.let { d -> date(d.optString("date"))?.takeIf { it >= today }?.let { AiDeadline(it, d.strOrNull("time", 5)?.takeIf { x -> toMinute(x) != null }, d.str("said", 80)) } }
            AiTask(
                id = t.str("id", 24).ifEmpty { "t${i + 1}" }, title = title, description = t.str("description", 500),
                plannedDay = t.dayRef("planned_day", today),
                partOfDay = t.strOrNull("part_of_day", 12)?.takeIf { it in setOf("matin", "apres-midi", "soir") },
                startTime = t.strOrNull("start_time", 5)?.takeIf { toMinute(it) != null },
                deadline = dl, durationMinutes = t.minutes("duration_minutes"),
                durationSource = oneOf(t.str("duration_source", 12), setOf("said", "estimated", "unknown"), "unknown"),
                priority = oneOf(t.str("priority", 8), setOf("low", "normal", "high", "urgent"), "normal"),
                sessionDays = t.optJSONArray("session_days")?.let { a -> (0 until a.length()).mapNotNull { date(a.optString(it))?.takeIf { d -> d >= today } }.distinct().sorted() } ?: emptyList(),
                sessionMinutes = t.minutes("session_minutes"), subtasks = subs, dependsOn = t.ids("depends_on"),
                project = t.strOrNull("project", 80), sourceQuote = t.str("source_quote", 400),
                confidence = oneOf(t.str("confidence", 8), setOf("high", "medium", "low"), "medium"),
            )
        }
        val constraints = o.optJSONArray("constraints")?.let { a ->
            (0 until minOf(a.length(), 30)).mapNotNull { k ->
                val c = a.optJSONObject(k) ?: return@mapNotNull null
                val d = date(c.optString("date"))?.takeIf { it >= today } ?: return@mapNotNull null
                val s = c.str("start_time", 5); val e = c.str("end_time", 5)
                val sm = toMinute(s) ?: return@mapNotNull null
                val em = if (e == "24:00") 1440 else toMinute(e) ?: return@mapNotNull null
                if (em <= sm) null else AiConstraint(d, s, e, c.str("reason", 120).ifEmpty { "Indisponible" }, c.str("said", 200))
            }
        } ?: emptyList()
        val q = o.optJSONArray("questions")?.let { a -> (0 until minOf(a.length(), 8)).map { a.optString(it).trim().take(200) }.filter(String::isNotEmpty) } ?: emptyList()
        return Understanding(tasks, constraints, q, o.str("summary", 600))
    }

    /** Sérialisation au format du schéma, renvoyée au serveur comme contexte du tour suivant. */
    fun toJson(u: Understanding): JSONObject {
        fun day(d: DayRef?) = d?.let { JSONObject().put("date", it.date.toString()).put("said", it.said) } ?: JSONObject.NULL
        fun n(v: Any?) = v ?: JSONObject.NULL
        return JSONObject()
            .put("tasks", JSONArray(u.tasks.map { t ->
                JSONObject().put("id", t.id).put("title", t.title).put("description", t.description).put("planned_day", day(t.plannedDay))
                    .put("part_of_day", n(t.partOfDay)).put("start_time", n(t.startTime))
                    .put("deadline", t.deadline?.let { JSONObject().put("date", it.date.toString()).put("time", n(it.time)).put("said", it.said) } ?: JSONObject.NULL)
                    .put("duration_minutes", n(t.durationMinutes)).put("duration_source", t.durationSource).put("priority", t.priority)
                    .put("session_days", JSONArray(t.sessionDays.map { it.toString() })).put("session_minutes", n(t.sessionMinutes))
                    .put("subtasks", JSONArray(t.subtasks.map { s ->
                        JSONObject().put("id", s.id).put("title", s.title).put("planned_day", day(s.plannedDay)).put("duration_minutes", n(s.durationMinutes))
                            .put("duration_source", s.durationSource).put("depends_on", JSONArray(s.dependsOn)).put("confidence", s.confidence)
                    }))
                    .put("depends_on", JSONArray(t.dependsOn)).put("project", n(t.project)).put("source_quote", t.sourceQuote).put("confidence", t.confidence)
            }))
            .put("constraints", JSONArray(u.constraints.map { c ->
                JSONObject().put("date", c.date.toString()).put("start_time", c.startTime).put("end_time", c.endTime).put("reason", c.reason).put("said", c.said)
            }))
            .put("questions", JSONArray(u.questions))
            .put("summary", u.summary)
    }

    private val PART_RANGE = mapOf("matin" to (480 to 720), "apres-midi" to (810 to 1080), "soir" to (1080 to 1350))
    private fun priorityOf(p: String) = when (p) { "low" -> Priority.LOW; "high" -> Priority.HIGH; "urgent" -> Priority.URGENT; else -> Priority.NORMAL }

    /**
     * Compréhension → tâches du planificateur. Les sous-tâches qui ont leur propre jour ou leur propre durée deviennent des
     * séances distinctes (regroupées par projet) ; les autres restent une liste à cocher de la tâche principale.
     * Identifiants de séance : « t1 » (tâche) ou « t1/t1.2 » (sous-tâche) — utilisés par [applyEdit].
     */
    fun toPlanInput(u: Understanding, now: Moment, learned: Map<String, List<Int>> = emptyMap()): PlanInput {
        val items = mutableListOf<VoiceTask>()
        val rawDeps = mutableMapOf<String, List<String>>()
        val lastItemOf = mutableMapOf<String, String>()
        val horizon = Horizon(now.date, now.date.plusDays(6))
        for (t in u.tasks) {
            val deadline = t.deadline?.let { Deadline(it.date, toMinute(it.time) ?: 1439, it.said, it.time == null) }
            val separate = t.subtasks.filter { it.plannedDay != null || it.durationMinutes != null }
            val checklist = t.subtasks.filter { it !in separate }
            val group = t.project ?: if (separate.isNotEmpty()) t.title else null
            fun base(id: String, title: String, ref: DayRef?, minutes: Int?, said: Boolean, subtasks: List<String> = emptyList()): VoiceTask {
                val n = VoiceParser.norm(title)
                val est = DurationEstimator.estimate(n, learned)
                val (a, b) = t.partOfDay?.let { PART_RANGE.getValue(it) } ?: (480 to 1350)
                val fixed = toMinute(t.startTime)
                var w = ref?.let { if (fixed != null && ref == t.plannedDay) Window(it.date, fixed, fixed, true, it.said) else Window(it.date, a, b, false, it.said) }
                if (deadline != null && w != null && (w.date > deadline.date || (w.date == deadline.date && w.start >= deadline.minute))) w = null
                return VoiceTask(id, title, n, deadline = deadline, window = w, horizon = horizon, urgent = t.priority == "urgent", low = t.priority == "low",
                    explicitMinutes = if (said) minutes else null, estimate = est, minutes = minutes ?: est.minutes, priority = priorityOf(t.priority),
                    group = group, subtasks = subtasks)
            }
            for (s in separate) {
                val it = base("${t.id}/${s.id}", s.title, s.plannedDay ?: t.plannedDay, s.durationMinutes, s.durationSource == "said")
                if (s.confidence == "low") it.notes += "À vérifier : compréhension incertaine."
                items += it; rawDeps[it.id] = s.dependsOn; lastItemOf[s.id] = it.id
            }
            if (checklist.isNotEmpty() || separate.isEmpty()) {
                val already = separate.sumOf { it.durationMinutes ?: 0 }
                // Avec des séances datées, il ne reste que la liste à cocher : on l'estime elle, pas le titre (sinon le travail compte deux fois).
                val rest = checklist.sumOf { s -> s.durationMinutes ?: DurationEstimator.estimate(VoiceParser.norm(s.title), learned).minutes }
                val own = t.durationMinutes?.let { maxOf(15, it - already) } ?: if (separate.isNotEmpty()) maxOf(15, rest) else null
                val it = base(t.id, t.title, t.plannedDay, own, t.durationSource == "said" && already == 0, checklist.map { s -> s.title })
                if (t.sessionDays.isNotEmpty()) {
                    it.recurrence = Recurrence(Recurrence.Type.DAYS, part = t.partOfDay, dates = t.sessionDays)
                    it.sessionMinutes = t.sessionMinutes ?: t.durationMinutes ?: 30
                    it.window = null
                }
                if (t.description.isNotEmpty()) it.notes += t.description
                if (t.confidence == "low") it.notes += "À vérifier : compréhension incertaine."
                items += it; rawDeps[it.id] = t.dependsOn; lastItemOf[t.id] = it.id
            } else {
                // Pas de séance « principale » : la tâche est terminée quand sa dernière sous-tâche l'est.
                lastItemOf[t.id] = "${t.id}/${separate.last().id}"
                for (it in items.filter { x -> x.id.startsWith("${t.id}/") }) rawDeps[it.id] = (rawDeps[it.id] ?: emptyList()) + t.dependsOn
            }
        }
        for (it in items) {
            it.dependsOn += (rawDeps[it.id] ?: emptyList()).mapNotNull { d -> lastItemOf[d] }.filter { d -> d != it.id }.distinct()
            val hoursLeft = it.deadline?.let { d -> ChronoUnit.DAYS.between(now.date, d.date) * 24 + (d.minute - now.minute) / 60.0 } ?: Double.POSITIVE_INFINITY
            it.level = when { it.urgent && hoursLeft <= 24 -> ReminderLevel.BRR; it.urgent -> ReminderLevel.HARCELEMENT; it.low -> ReminderLevel.NORMAL; else -> ReminderLevel.INSISTANT }
            if (it.priority == Priority.NORMAL && hoursLeft <= 48) it.priority = Priority.HIGH
            it.explicitMinutes?.let { m -> it.notes += "Durée indiquée : $m min." }
            val last = (listOfNotNull(it.horizon?.until, it.window?.date, it.deadline?.date) + (it.recurrence?.dates ?: emptyList())).max()
            it.horizon = Horizon(now.date, last)
        }
        val constraints = u.constraints.map { c -> Constraint(c.date, toMinute(c.startTime)!!, if (c.endTime == "24:00") 1440 else toMinute(c.endTime)!!, c.reason) }
        return PlanInput(items, constraints, u.questions, u.summary)
    }

    /** Retouche d'une séance avant validation : modifie la compréhension (référence de la suite de la conversation). */
    fun applyEdit(u: Understanding, itemId: String, edit: Edit): Understanding {
        val tid = itemId.substringBefore('/'); val sid = itemId.substringAfter('/', "").ifEmpty { null }
        val t = u.tasks.firstOrNull { it.id == tid } ?: return u
        fun clean(tasks: List<AiTask>, gone: Set<String>) = tasks.map { o ->
            o.copy(dependsOn = o.dependsOn - gone, subtasks = o.subtasks.map { s -> s.copy(dependsOn = s.dependsOn - gone) })
        }
        fun replace(nt: AiTask) = u.copy(tasks = u.tasks.map { if (it.id == tid) nt else it })
        return when (edit) {
            Edit.Remove -> when {
                sid != null -> u.copy(tasks = clean(replace(t.copy(subtasks = t.subtasks.filter { it.id != sid })).tasks, setOf(sid)))
                t.subtasks.any { it.plannedDay != null || it.durationMinutes != null } ->
                    replace(t.copy(subtasks = t.subtasks.filter { it.plannedDay != null || it.durationMinutes != null }))
                else -> u.copy(tasks = clean(u.tasks.filter { it.id != tid }, setOf(tid)))
            }
            is Edit.Minutes -> {
                val m = edit.minutes.coerceIn(5, MAX_MINUTES)
                if (sid != null) replace(t.copy(subtasks = t.subtasks.map { if (it.id == sid) it.copy(durationMinutes = m, durationSource = "said") else it }))
                else replace(t.copy(durationMinutes = m, durationSource = "said", sessionMinutes = if (t.sessionDays.isNotEmpty()) m else t.sessionMinutes))
            }
            is Edit.Day -> {
                val ref = edit.date?.let { DayRef(it, "choisi à la main") }
                if (sid != null) replace(t.copy(subtasks = t.subtasks.map { if (it.id == sid) it.copy(plannedDay = ref) else it }))
                else replace(t.copy(plannedDay = ref))
            }
            is Edit.Prio -> replace(t.copy(priority = edit.priority))
        }
    }

    sealed interface Edit {
        data object Remove : Edit
        data class Minutes(val minutes: Int) : Edit
        data class Day(val date: LocalDate?) : Edit
        data class Prio(val priority: String) : Edit
    }
}
