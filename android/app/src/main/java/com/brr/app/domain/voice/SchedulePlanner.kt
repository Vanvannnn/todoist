package com.brr.app.domain.voice

import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Construction d'un planning réaliste (port fidèle de web/js/core/planner.js).
 * Règles : jamais pendant un engagement ni ses trajets, pauses entre blocs, vrais repas, plafond quotidien,
 * marge avant les échéances, répartition sur plusieurs jours. [validate] vérifie ces règles.
 */
object SchedulePlanner {
    data class Meal(val from: Int, val to: Int, val dur: Int)
    data class Settings(
        val dayStart: Int = 8 * 60, val dayEnd: Int = 22 * 60 + 30,
        val lunch: Meal = Meal(11 * 60 + 45, 14 * 60, 60), val dinner: Meal = Meal(19 * 60, 21 * 60, 45),
        val breakMin: Int = 15, val maxBlock: Int = 90, val minBlock: Int = 25,
        val maxFocusPerDay: Int = 5 * 60, val deadlineMargin: Int = 60, val maxDays: Int = 14,
    )

    /** Engagement du calendrier, en heure locale (un événement sur plusieurs jours est découpé). */
    data class BusyEvent(
        val date: LocalDate, val start: Int, val end: Int, val title: String, val location: String? = null,
        val travelBefore: Int? = null, val travelAfter: Int? = null,
    )

    enum class Kind { TASK, EVENT, TRAVEL, MEAL, BREAK, PAST }
    data class Block(
        val start: Int, val end: Int, val kind: Kind, val title: String, val taskId: String? = null,
        val location: String? = null, val level: ReminderLevel? = null,
    )
    data class Day(val date: LocalDate, val focus: Int, val free: Int, val blocks: List<Block>)
    data class Miss(val taskId: String, val title: String, val minutes: Int, val reason: String, val alternatives: List<String>)
    data class Result(val days: List<Day>, val unplaced: List<Miss>, val warnings: List<String>, val settings: Settings) {
        val feasible get() = unplaced.isEmpty()
    }

    private val PART_RANGE = mapOf("matin" to (480 to 720), "apres-midi" to (810 to 1080), "soir" to (1080 to 1350))
    private fun weight(p: Priority) = when (p) { Priority.URGENT -> 3; Priority.HIGH -> 2; Priority.NORMAL -> 1; Priority.LOW -> 0 }

    fun chunks(total: Int, maxBlock: Int, minBlock: Int): List<Int> {
        val n = maxOf(1, ceil(total.toDouble() / maxBlock).toInt())
        val base = PlanDates.round5(total.toDouble() / n)
        return List(n) { if (it == n - 1) maxOf(minBlock, total - base * (n - 1)) else base }
    }

    private fun freeGaps(busy: List<Block>, from: Int, to: Int, breakMin: Int): List<Pair<Int, Int>> {
        // Les blocs de travail sont entourés d'une pause ; repas, trajets et engagements comptent déjà comme coupure.
        val iv = busy.map { if (it.kind == Kind.TASK) (it.start - breakMin) to (it.end + breakMin) else it.start to it.end }.sortedBy { it.first }
        val gaps = mutableListOf<Pair<Int, Int>>(); var cur = from
        for ((a, b) in iv) {
            if (b <= cur) continue
            if (a >= to) break
            if (a > cur) gaps += cur to minOf(a, to)
            cur = maxOf(cur, b)
        }
        if (cur < to) gaps += cur to to
        return gaps
    }

    private fun shift(date: LocalDate, minute: Int, delta: Int): Moment {
        var m = minute + delta; var d = date
        while (m < 0) { m += 1440; d = d.minusDays(1) }
        while (m >= 1440) { m -= 1440; d = d.plusDays(1) }
        return Moment(d, m)
    }
    private fun before(a: Moment, b: Moment) = a.date < b.date || (a.date == b.date && a.minute < b.minute)

    private class WorkDay(val date: LocalDate) { val busy = mutableListOf<Block>(); var focus = 0; val tasks = mutableSetOf<String>() }
    private class Session(
        val task: VoiceTask, val latest: Moment?, val from: LocalDate, val window: Window?, val dur: Int,
        val onlyDay: LocalDate? = null, val part: String? = null,
    )

    fun plan(tasks: List<VoiceTask>, events: List<BusyEvent>, now: Moment, settings: Settings = Settings()): Result {
        val s = settings
        val warnings = mutableListOf<String>(); val unplaced = mutableListOf<Miss>()
        val maxDate = now.date.plusDays((s.maxDays - 1).toLong())
        var lastDate = now.date
        for (t in tasks) {
            val end = listOfNotNull(t.horizon?.until, t.deadline?.date).maxOrNull() ?: now.date.plusDays(6)
            if (end > lastDate) lastDate = end
        }
        if (lastDate > maxDate) lastDate = maxDate

        val days = mutableListOf<WorkDay>()
        var d = now.date
        while (d <= lastDate) {
            val day = WorkDay(d)
            for (e in events.filter { it.date == d }) {
                day.busy += Block(e.start, e.end, Kind.EVENT, e.title, location = e.location)
                // Un déplacement ne prend jamais zéro minute quand un lieu est indiqué.
                val tb = e.travelBefore ?: if (e.location != null) 15 else 0
                val ta = e.travelAfter ?: if (e.location != null) 15 else 0
                if (tb > 0) day.busy += Block(e.start - tb, e.start, Kind.TRAVEL, "Trajet")
                if (ta > 0) day.busy += Block(e.end, e.end + ta, Kind.TRAVEL, "Trajet")
            }
            if (d == now.date) day.busy += Block(0, ceil((now.minute + 10) / 5.0).toInt() * 5, Kind.PAST, "")
            for (meal in listOf(s.lunch, s.dinner)) {
                val passed = d == now.date && now.minute >= meal.to
                if (passed) continue
                val gap = freeGaps(day.busy, meal.from, meal.to, 0).firstOrNull { (a, b) -> b - a >= meal.dur }
                val name = if (meal === s.lunch) "déjeuner" else "dîner"
                if (gap != null) day.busy += Block(gap.first, gap.first + meal.dur, Kind.MEAL, name.replaceFirstChar { it.uppercase() }.replace("Dejeuner", "Déjeuner"))
                else warnings += "${PlanDates.fmtDay(d, now.date)} : pas de place pour un vrai $name entre ${PlanDates.fmtTime(meal.from)} et ${PlanDates.fmtTime(meal.to)}."
            }
            days += day
            d = d.plusDays(1)
        }
        fun dayIndex(date: LocalDate) = days.indexOfFirst { it.date == date }

        // Séances à placer.
        val sessions = mutableListOf<Session>()
        for (t in tasks) {
            val latest = t.deadline?.let { shift(it.date, it.minute, -s.deadlineMargin) }
            val from = t.horizon?.from?.takeIf { it > now.date } ?: now.date
            when (t.recurrence?.type) {
                Recurrence.Type.DAILY -> {
                    val until = listOf(t.horizon?.until ?: lastDate, latest?.date ?: lastDate, lastDate).min()
                    var x = from
                    while (x <= until) { sessions += Session(t, latest, from, null, t.sessionMinutes ?: 30, onlyDay = x, part = t.recurrence?.part); x = x.plusDays(1) }
                }
                Recurrence.Type.COUNT -> repeat(t.recurrence!!.n) { sessions += Session(t, latest, from, null, minOf(t.minutes, s.maxBlock)) }
                null -> for (dur in chunks(t.minutes, s.maxBlock, s.minBlock)) sessions += Session(t, latest, from, t.window, dur)
            }
        }

        // Ordre : créneaux imposés, puis échéance la plus proche, priorité, durée ; dépendances respectées.
        fun key(x: Session) = listOf(if (x.window?.fixed == true) "0" else "1",
            x.latest?.let { it.date.toString() + "%04d".format(it.minute) } ?: "9999",
            "${9 - weight(x.task.priority)}", "%04d".format(9999 - x.dur)).joinToString("|")
        val pending = sessions.sortedBy(::key).toMutableList()
        val ordered = mutableListOf<Session>(); val doneTasks = mutableSetOf<String>()
        while (pending.isNotEmpty()) {
            var i = pending.indexOfFirst { x -> x.task.dependsOn.all { id -> id in doneTasks || tasks.none { it.id == id } } }
            if (i < 0) i = 0 // cycle : on ignore la dépendance plutôt que de bloquer
            val x = pending.removeAt(i)
            ordered += x
            if (pending.none { it.task === x.task }) doneTasks += x.task.id
        }

        val placedEnd = mutableMapOf<String, Moment>()

        fun tryPlace(x: Session, dur: Int, depEnd: Moment?): Boolean {
            val t = x.task
            var cands: List<LocalDate>
            if (x.onlyDay != null) cands = listOf(x.onlyDay)
            else {
                val first = maxOf(x.from, depEnd?.date ?: x.from)
                val last = if (x.latest != null && x.latest.date < lastDate) x.latest.date else lastDate
                cands = days.map { it.date }.filter { it >= first && it <= last }
                val w = x.window
                if (w != null && w.date in cands) cands = listOf(w.date) + cands.filter { it != w.date }
            }
            var best: Triple<Int, WorkDay, Int>? = null
            for (date in cands) {
                val day = days.getOrNull(dayIndex(date)) ?: continue
                val w = x.window
                val fixedHere = w?.fixed == true && w.date == date
                if (!fixedHere && day.focus + dur > s.maxFocusPerDay) continue
                var from = s.dayStart; var to = s.dayEnd
                // Créneau souhaité (« demain après-midi ») : même moment de la journée, y compris les jours de repli.
                if (w != null && !w.fixed) { from = maxOf(from, w.start); to = minOf(to, w.end) }
                x.part?.let { p -> PART_RANGE[p]?.let { from = it.first; to = it.second } }
                if (x.latest != null && x.latest.date == date) to = minOf(to, x.latest.minute)
                if (depEnd != null && depEnd.date == date) from = maxOf(from, depEnd.minute + s.breakMin)
                var start: Int? = null
                if (fixedHere) {
                    val end = w!!.start + dur
                    val clash = day.busy.any { it.kind != Kind.MEAL && it.start < end && it.end > w.start }
                    if (!clash) start = w.start
                    else if (warnings.none { it.contains(t.title) }) warnings += "« ${t.title} » : l'horaire demandé (${PlanDates.fmtTime(w.start)}) est déjà occupé, un autre créneau est proposé."
                }
                if (start == null) start = freeGaps(day.busy, from, to, s.breakMin).firstOrNull { (a, b) -> b - a >= dur }?.first
                if (start == null) continue
                val idx = dayIndex(date)
                // Le jour souhaité d'abord, puis les jours les plus proches de lui.
                val preferred = if (w != null) (if (w.date == date) -1000 else abs(ChronoUnit.DAYS.between(w.date, date)).toInt() * 40) else 0
                val score = preferred + day.focus + (if (t.id in day.tasks) 600 else 0) + idx * 15
                if (best == null || score < best.first) best = Triple(score, day, start)
                if (fixedHere && start == w!!.start) break
            }
            val (_, day, start) = best ?: return false
            val w = x.window
            if (w != null && day.date != w.date && warnings.none { it.startsWith("« ${t.title} »") }) {
                warnings += "« ${t.title} » : « ${w.label} » n'a pas de créneau libre assez long, proposé ${PlanDates.fmtDay(day.date, now.date).lowercase()} à ${PlanDates.fmtTime(start)}."
            }
            day.busy += Block(start, start + dur, Kind.TASK, t.title, taskId = t.id, level = t.level)
            day.focus += dur
            day.tasks += t.id
            val end = Moment(day.date, start + dur)
            val prev = placedEnd[t.id]
            if (prev == null || before(prev, end)) placedEnd[t.id] = end
            return true
        }

        fun miss(x: Session, dur: Int): Miss {
            val t = x.task
            val reason = when {
                x.latest != null -> "pas assez de temps libre avant l'échéance (${PlanDates.fmtDay(t.deadline!!.date, now.date).lowercase()} ${PlanDates.fmtTime(t.deadline!!.minute)})"
                x.onlyDay != null -> "aucun créneau libre ${PlanDates.fmtDay(x.onlyDay, now.date).lowercase()}"
                else -> "aucun créneau libre sur la période"
            }
            val alternatives = mutableListOf<String>()
            val freeBefore = days.filter { x.latest == null || it.date <= x.latest.date }.sumOf { day ->
                freeGaps(day.busy, s.dayStart, if (x.latest?.date == day.date) minOf(s.dayEnd, x.latest.minute) else s.dayEnd, s.breakMin).sumOf { it.second - it.first }
            }
            val largest = days.flatMap { day -> freeGaps(day.busy, s.dayStart, s.dayEnd, s.breakMin).map { it.second - it.first } }.maxOrNull()?.coerceAtLeast(0) ?: 0
            if (largest >= s.minBlock && largest < dur) alternatives += "Réduire cette séance à ${PlanDates.round5((minOf(largest, dur) - 5).toDouble())} min."
            val movable = ordered.filter { it.task !== t && it.task.deadline == null && weight(it.task.priority) < weight(t.priority) }
            if (movable.isNotEmpty()) alternatives += "Reporter « ${movable[0].task.title} » (moins prioritaire) pour libérer du temps."
            alternatives += if (x.latest != null) {
                if (freeBefore > 0) "Demander un délai ou commencer plus tôt dans la journée." else "Demander un délai : il ne reste aucun temps libre avant l'échéance."
            } else "Reporter cette tâche à la semaine suivante."
            alternatives += "Augmenter le temps de travail quotidien autorisé dans les réglages."
            return Miss(t.id, t.title, dur, reason, alternatives)
        }

        for (x in ordered) {
            val depEnd = x.task.dependsOn.mapNotNull { placedEnd[it] }.sortedWith(compareBy<Moment> { it.date }.thenBy { it.minute }).lastOrNull()
            var ok = tryPlace(x, x.dur, depEnd)
            if (!ok && x.dur >= s.minBlock * 2 && x.window?.fixed != true) {
                val half = PlanDates.round5(x.dur / 2.0)
                if (tryPlace(x, half, depEnd)) {
                    if (!tryPlace(x, x.dur - half, depEnd)) unplaced += miss(x, x.dur - half)
                    continue
                }
            }
            if (!ok) unplaced += miss(x, x.dur)
        }

        // Pauses visibles après chaque bloc de travail, quand la place existe.
        val out = days.map { day ->
            val extra = day.busy.filter { it.kind == Kind.TASK }.mapNotNull { b ->
                val st = b.end; val en = b.end + s.breakMin
                if (day.busy.none { o -> o !== b && o.kind != Kind.PAST && o.start < en && o.end > st } && en <= s.dayEnd + s.breakMin) Block(st, en, Kind.BREAK, "Pause") else null
            }
            day.busy += extra
            val free = freeGaps(day.busy.filter { it.kind != Kind.BREAK }, s.dayStart, s.dayEnd, 0).sumOf { it.second - it.first }
            Day(day.date, day.focus, free, day.busy.filter { it.kind != Kind.PAST }.sortedWith(compareBy<Block> { it.start }.thenBy { it.end }))
        }
        return Result(out, unplaced, warnings, s)
    }

    /** Vérifie les règles obligatoires d'un planning. Retourne les violations (vide = conforme). */
    fun validate(r: Result, tasks: List<VoiceTask>, now: Moment): List<String> {
        val s = r.settings; val errors = mutableListOf<String>()
        for (day in r.days) {
            val tb = day.blocks.filter { it.kind == Kind.TASK }
            val hard = day.blocks.filter { it.kind == Kind.EVENT || it.kind == Kind.TRAVEL || it.kind == Kind.MEAL }
            var focus = 0
            for (b in tb) {
                focus += b.end - b.start
                if (b.start < s.dayStart || b.end > s.dayEnd) errors += "${day.date} ${b.title} hors journée"
                if (b.end - b.start > s.maxBlock) errors += "${day.date} ${b.title} bloc trop long"
                for (h in hard) if (h.start < b.end && h.end > b.start) errors += "${day.date} ${b.title} chevauche ${h.title}"
                for (o in tb) if (o !== b && o.start >= b.start && o.start < b.end + s.breakMin) errors += "${day.date} ${b.title} → ${o.title} sans pause"
                tasks.firstOrNull { it.id == b.taskId }?.deadline?.let { dl ->
                    val lim = shift(dl.date, dl.minute, -s.deadlineMargin)
                    if (day.date > lim.date || (day.date == lim.date && b.end > lim.minute)) errors += "${day.date} ${b.title} après l'échéance"
                }
                if (day.date == now.date && b.start < now.minute) errors += "${b.title} dans le passé"
            }
            if (focus > s.maxFocusPerDay && tb.none { b -> tasks.firstOrNull { it.id == b.taskId }?.window?.fixed == true }) errors += "${day.date} journée surchargée ($focus min)"
            val label = PlanDates.fmtDay(day.date, now.date)
            if (day.date > now.date && tb.isNotEmpty() && day.blocks.none { it.kind == Kind.MEAL } && r.warnings.none { it.startsWith(label) }) errors += "${day.date} aucun repas"
        }
        return errors
    }
}
