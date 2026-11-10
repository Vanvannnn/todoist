package com.brr.app.domain

import com.brr.app.domain.model.ReminderLevel
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Réglages qui influencent le calcul des rappels (sous-ensemble des préférences utilisateur). */
data class ReminderSettings(
    val brrModeEnabled: Boolean = true,
    val progressiveEscalation: Boolean = true,
    val intervalsMinutes: List<Int> = listOf(5, 5, 5, 5, 10),
    val maxRelances: Int = 5,
    val allowedHoursEnabled: Boolean = false,
    val allowedStartMinute: Int = 8 * 60,
    val allowedEndMinute: Int = 22 * 60,
    val allDayReminderMinute: Int = 9 * 60,
    /** Au-delà de ce délai, une échéance ratée (téléphone éteint…) ne déclenche plus de rappel de rattrapage. */
    val catchUpWindowMinutes: Int = 12 * 60,
    /** Mode pause : engagements du calendrier (trajet inclus) pendant lesquels les rappels sont retenus. */
    val pauses: List<PauseWindow> = emptyList(),
    val pauseEnabled: Boolean = true,
)

/** Intervalle de pause [start, end[ en millisecondes (fin d'engagement + trajet). */
data class PauseWindow(val start: Long, val end: Long, val title: String)

/** État du cycle de rappel d'une tâche (persisté avec la tâche). */
data class ReminderState(
    val snoozedUntil: Long? = null,
    /** Nombre de déclenchements depuis l'échéance (0 = l'échéance n'a pas encore sonné). */
    val escalationStep: Int = 0,
    val lastFiredAt: Long? = null,
    val snoozeCount: Int = 0,
)

/** Vue minimale d'une tâche nécessaire au planificateur. */
data class PlannerTask(
    val dueAt: Long?,
    val hasTime: Boolean,
    val completed: Boolean,
    val remindersEnabled: Boolean,
    val level: ReminderLevel,
    val nagIntervalMinutes: Int?,
    val preOffsetsMinutes: List<Int>,
    val state: ReminderState,
    /** Niveau 4 uniquement : autorisé explicitement à sonner pendant une pause. */
    val bypassPause: Boolean = false,
)

enum class TriggerKind { PRE_REMINDER, DUE, SNOOZE, ESCALATION }

data class PlannedTrigger(val at: Long, val kind: TriggerKind)

/** Ce qu'il faut afficher lorsqu'une alarme sonne, et le nouvel état de la tâche. */
data class FirePlan(
    val kind: TriggerKind,
    val level: ReminderLevel,
    val newState: ReminderState,
    val isFinalWarning: Boolean,
    val minutesLate: Long,
)

object ReminderPlanner {
    private const val MINUTE = 60_000L
    /** Tolérance : une alarme qui sonne un peu en avance est considérée comme à l'heure. */
    private const val TOLERANCE = 30_000L

    /** Heure de référence de l'échéance (les tâches « journée » sonnent à l'heure configurée). */
    fun anchor(task: PlannerTask, settings: ReminderSettings, zone: ZoneId): Long? {
        val due = task.dueAt ?: return null
        if (task.hasTime) return due
        val day = Instant.ofEpochMilli(due).atZone(zone).toLocalDate()
        val m = settings.allDayReminderMinute.coerceIn(0, 24 * 60 - 1)
        return day.atTime(LocalTime.of(m / 60, m % 60)).atZone(zone).toInstant().toEpochMilli()
    }

    fun nextTrigger(task: PlannerTask, settings: ReminderSettings, now: Long, zone: ZoneId): PlannedTrigger? {
        val raw = rawNextTrigger(task, settings, now, zone) ?: return null
        // Plage autorisée et pauses peuvent se repousser l'une l'autre : on itère jusqu'à stabilité.
        var at = raw.at
        repeat(8) {
            val next = applyPauses(applyAllowedWindow(at, settings, zone), settings, task)
            if (next == at) return raw.copy(at = at)
            at = next
        }
        return raw.copy(at = at)
    }

    /**
     * Reporte un instant tombant pendant une pause à la fin de celle-ci (jamais de rappel perdu, seulement décalé).
     * Le niveau 4 ne traverse une pause que si la tâche l'autorise explicitement.
     */
    fun applyPauses(at: Long, settings: ReminderSettings, task: PlannerTask? = null): Long {
        if (!settings.pauseEnabled) return at
        if (task != null && task.bypassPause && task.level == ReminderLevel.BRR) return at
        var t = at
        for (p in settings.pauses.sortedBy { it.start }) if (t >= p.start && t < p.end) t = p.end
        return t
    }

    fun activePause(now: Long, settings: ReminderSettings): PauseWindow? =
        if (!settings.pauseEnabled) null else settings.pauses.firstOrNull { now >= it.start && now < it.end }

    private fun rawNextTrigger(task: PlannerTask, settings: ReminderSettings, now: Long, zone: ZoneId): PlannedTrigger? {
        if (task.completed || !task.remindersEnabled) return null
        val st = task.state
        st.snoozedUntil?.let { return PlannedTrigger(maxOf(it, now), TriggerKind.SNOOZE) }
        val anchor = anchor(task, settings, zone) ?: return null

        if (st.escalationStep == 0) {
            val last = st.lastFiredAt ?: Long.MIN_VALUE
            val pre = task.preOffsetsMinutes.filter { it > 0 }
                .map { anchor - it * MINUTE }
                .filter { it > now && it > last }
                .minOrNull()
            if (anchor > now) return if (pre != null && pre < anchor) PlannedTrigger(pre, TriggerKind.PRE_REMINDER)
            else PlannedTrigger(anchor, TriggerKind.DUE)
            // Échéance passée sans avoir sonné : rattrapage immédiat si raisonnablement récent.
            return if (now - anchor <= settings.catchUpWindowMinutes * MINUTE) PlannedTrigger(now, TriggerKind.DUE) else null
        }

        if (st.escalationStep - 1 >= settings.maxRelances) return null
        val last = st.lastFiredAt ?: anchor
        val at = last + nagInterval(task, st.escalationStep, settings) * MINUTE
        return PlannedTrigger(maxOf(at, now), TriggerKind.ESCALATION)
    }

    /** Intervalle avant la relance suivante, [step] étant le nombre de déclenchements déjà effectués. */
    fun nagInterval(task: PlannerTask, step: Int, settings: ReminderSettings): Int {
        task.nagIntervalMinutes?.let { if (it > 0) return it }
        val list = settings.intervalsMinutes.filter { it > 0 }.ifEmpty { listOf(5) }
        return list[(step - 1).coerceIn(0, list.lastIndex)]
    }

    /** Détermine ce qu'il faut faire au moment où l'alarme sonne. */
    fun onFire(task: PlannerTask, settings: ReminderSettings, now: Long, zone: ZoneId): FirePlan? {
        if (task.completed || !task.remindersEnabled) return null
        val st = task.state
        val anchor = anchor(task, settings, zone)
        val kind = when {
            st.snoozedUntil != null && st.snoozedUntil <= now + TOLERANCE -> TriggerKind.SNOOZE
            st.snoozedUntil != null -> return null // alarme obsolète : le report n'est pas encore échu
            anchor == null -> return null
            st.escalationStep == 0 && anchor > now + TOLERANCE -> TriggerKind.PRE_REMINDER
            st.escalationStep == 0 -> TriggerKind.DUE
            // Limite de relances atteinte : une alarme parasite ne doit rien déclencher.
            st.escalationStep - 1 >= settings.maxRelances -> return null
            else -> TriggerKind.ESCALATION
        }
        val newState = when (kind) {
            TriggerKind.PRE_REMINDER -> st.copy(lastFiredAt = now)
            TriggerKind.DUE -> st.copy(escalationStep = 1, lastFiredAt = now)
            TriggerKind.SNOOZE -> {
                // Un report qui sonne avant l'échéance ne démarre pas l'escalade.
                val beforeDue = st.escalationStep == 0 && anchor != null && anchor > now + TOLERANCE
                st.copy(snoozedUntil = null, escalationStep = if (beforeDue) 0 else st.escalationStep + 1, lastFiredAt = now)
            }
            TriggerKind.ESCALATION -> st.copy(escalationStep = st.escalationStep + 1, lastFiredAt = now)
        }
        val isFinal = newState.escalationStep > 0 && newState.escalationStep - 1 >= settings.maxRelances
        val late = if (anchor != null && now > anchor) (now - anchor) / MINUTE else 0
        return FirePlan(kind, effectiveLevel(task.level, kind, newState.escalationStep, settings), newState, isFinal, late)
    }

    fun effectiveLevel(taskLevel: ReminderLevel, kind: TriggerKind, stepAfterFire: Int, settings: ReminderSettings): ReminderLevel {
        val cap = if (settings.brrModeEnabled) taskLevel else taskLevel.atMost(ReminderLevel.HARCELEMENT)
        if (kind == TriggerKind.PRE_REMINDER || stepAfterFire <= 0) return ReminderLevel.NORMAL
        if (!settings.progressiveEscalation) return cap
        return ReminderLevel.fromIndex(stepAfterFire - 1).atMost(cap)
    }

    /** Repousse un déclenchement en dehors des horaires autorisés au début de la plage suivante. */
    fun applyAllowedWindow(at: Long, settings: ReminderSettings, zone: ZoneId): Long {
        if (!settings.allowedHoursEnabled) return at
        val start = settings.allowedStartMinute.coerceIn(0, 1439)
        val end = settings.allowedEndMinute.coerceIn(0, 1439)
        if (start == end) return at
        val t = ZonedDateTime.ofInstant(Instant.ofEpochMilli(at), zone)
        if (isAllowed(t.hour * 60 + t.minute, start, end)) return at
        var next = t.toLocalDate().atTime(LocalTime.of(start / 60, start % 60)).atZone(zone)
        if (!next.isAfter(t)) next = next.plusDays(1)
        return next.toInstant().toEpochMilli()
    }

    fun isAllowed(minuteOfDay: Int, start: Int, end: Int): Boolean =
        if (start < end) minuteOfDay in start until end else minuteOfDay >= start || minuteOfDay < end
}

/** Transitions d'état déclenchées par l'utilisateur. */
object ReminderStateTransitions {
    fun snooze(state: ReminderState, until: Long): ReminderState = state.copy(
        snoozedUntil = until,
        // On redémarre l'escalade en douceur, mais on garde la trace du nombre de reports.
        escalationStep = if (state.escalationStep > 0) 1 else 0,
        snoozeCount = state.snoozeCount + 1,
    )

    fun reset(): ReminderState = ReminderState()
}
