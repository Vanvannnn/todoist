package com.brr.app.reminder

import com.brr.app.data.db.TaskWithDetails
import com.brr.app.domain.FirePlan
import com.brr.app.domain.RecurrenceCalculator
import com.brr.app.domain.ReminderPlanner
import com.brr.app.domain.ReminderSettings
import com.brr.app.domain.ReminderState
import com.brr.app.domain.ReminderStateTransitions
import com.brr.app.domain.model.HistoryType
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.RepeatType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneId

/** Abstraction d'AlarmManager (permet de tester la programmation/annulation sans Android). */
interface AlarmScheduler {
    fun schedule(taskId: Long, triggerAt: Long, level: ReminderLevel)
    fun cancel(taskId: Long)
    fun isScheduled(taskId: Long): Boolean
    fun canScheduleExact(): Boolean
}

/** Retire une notification de rappel affichée. */
fun interface ReminderPresenter {
    fun dismiss(taskId: Long)
}

/** Accès aux données nécessaires au cycle de rappel. */
interface ReminderStore {
    suspend fun task(id: Long): TaskWithDetails?
    suspend fun schedulableTasks(): List<TaskWithDetails>
    suspend fun staleScheduledIds(): List<Long>
    suspend fun saveState(taskId: Long, state: ReminderState, next: Long?)
    suspend fun markCompleted(taskId: Long, at: Long)
    suspend fun markOpen(taskId: Long)
    /** Déplace l'échéance et réinitialise l'état de rappel. */
    suspend fun moveDue(taskId: Long, newDue: Long, hasTime: Boolean)
    suspend fun setRemindersEnabled(taskId: Long, enabled: Boolean)
    suspend fun resetSubtasks(taskId: Long)
    suspend fun log(taskId: Long?, title: String, type: HistoryType, detail: String = "")
}

data class FireOutcome(val task: TaskWithDetails, val plan: FirePlan, val nextAt: Long?)

data class VerificationReport(val scheduled: Int, val repaired: Int, val cancelled: Int)

/**
 * Orchestration du cycle de vie des rappels : programmation, déclenchement, escalade,
 * report, validation (annulation immédiate) et reprogrammation globale (démarrage, mise à jour…).
 */
class ReminderCoordinator(
    private val store: ReminderStore,
    private val scheduler: AlarmScheduler,
    private val presenter: ReminderPresenter,
    private val settings: suspend () -> ReminderSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private companion object {
        const val MINUTE = 60_000L
        /** Une alarme qui sonne bien avant l'heure prévue est considérée obsolète. */
        const val STALE_MARGIN = 60_000L
    }

    /** Sérialise toutes les opérations : évite qu'une reprogrammation globale écrase l'état d'une alarme en cours. */
    private val mutex = Mutex()

    /** Recalcule et programme (ou annule) le prochain rappel d'une tâche. Retourne l'heure programmée. */
    suspend fun reschedule(taskId: Long): Long? = mutex.withLock { rescheduleLocked(taskId) }

    private suspend fun rescheduleLocked(taskId: Long): Long? {
        val t = store.task(taskId)
        if (t == null) { scheduler.cancel(taskId); return null }
        return apply(t, t.task.reminderState, settings())
    }

    private suspend fun apply(t: TaskWithDetails, state: ReminderState, s: ReminderSettings): Long? {
        val planner = t.toPlannerTask().copy(state = state)
        val next = ReminderPlanner.nextTrigger(planner, s, clock(), zone())
        if (next != null) scheduler.schedule(t.task.id, next.at, t.task.reminderLevel) else scheduler.cancel(t.task.id)
        store.saveState(t.task.id, state, next?.at)
        return next?.at
    }

    /** Reprogramme tout (démarrage, mise à jour, changement d'heure, vérification périodique). */
    suspend fun rescheduleAll(): VerificationReport = mutex.withLock {
        val s = settings()
        var scheduled = 0
        var repaired = 0
        for (t in store.schedulableTasks()) {
            val wasScheduled = t.task.nextReminderAt != null && scheduler.isScheduled(t.task.id)
            val at = apply(t, t.task.reminderState, s)
            if (at != null) {
                scheduled++
                if (!wasScheduled && t.task.nextReminderAt != null) repaired++
            }
        }
        val stale = store.staleScheduledIds()
        for (id in stale) {
            scheduler.cancel(id)
            store.task(id)?.let { store.saveState(id, it.task.reminderState, null) }
        }
        VerificationReport(scheduled, repaired, stale.size)
    }

    /** Appelé quand l'alarme d'une tâche sonne. Retourne ce qu'il faut afficher, ou null si rien. */
    suspend fun handleFire(taskId: Long): FireOutcome? = mutex.withLock {
        val t = store.task(taskId) ?: run { scheduler.cancel(taskId); return@withLock null }
        val s = settings()
        val now = clock()
        val expected = t.task.nextReminderAt
        if (expected != null && expected > now + STALE_MARGIN) {
            // Alarme obsolète (la tâche a été modifiée entre-temps) : on se contente de reprogrammer.
            apply(t, t.task.reminderState, s)
            return@withLock null
        }
        val plan = ReminderPlanner.onFire(t.toPlannerTask(), s, now, zone())
        if (plan == null) { apply(t, t.task.reminderState, s); return@withLock null }
        val nextAt = apply(t, plan.newState, s)
        if (plan.kind == com.brr.app.domain.TriggerKind.DUE || plan.isFinalWarning) {
            store.log(taskId, t.task.title, HistoryType.REMINDER_FIRED,
                if (plan.isFinalWarning) "Dernier avertissement (${plan.level.label})" else "Échéance (${plan.level.label})")
        }
        FireOutcome(t, plan, nextAt)
    }

    /** Termine une tâche : annule immédiatement tous ses rappels ; les tâches répétitives passent à l'occurrence suivante. */
    suspend fun complete(taskId: Long): Long? = mutex.withLock {
        scheduler.cancel(taskId)
        presenter.dismiss(taskId)
        val t = store.task(taskId) ?: return@withLock null
        val now = clock()
        val task = t.task
        val nextDue = if (task.repeatType != RepeatType.NONE && task.dueAt != null)
            RecurrenceCalculator.next(task.dueAt, task.repeatType, task.repeatInterval, task.repeatDays, now, zone()) else null
        if (nextDue != null) {
            store.moveDue(taskId, nextDue, task.hasTime)
            store.resetSubtasks(taskId)
            store.log(taskId, task.title, HistoryType.COMPLETED, "Occurrence terminée, prochaine programmée")
            rescheduleLocked(taskId)
            nextDue
        } else {
            store.markCompleted(taskId, now)
            store.saveState(taskId, ReminderStateTransitions.reset(), null)
            store.log(taskId, task.title, HistoryType.COMPLETED)
            null
        }
    }

    suspend fun reopen(taskId: Long): Unit = mutex.withLock {
        val t = store.task(taskId) ?: return@withLock
        store.markOpen(taskId)
        store.saveState(taskId, ReminderStateTransitions.reset(), null)
        store.log(taskId, t.task.title, HistoryType.REOPENED)
        rescheduleLocked(taskId)
    }

    suspend fun snooze(taskId: Long, minutes: Int): Long? = mutex.withLock {
        presenter.dismiss(taskId)
        val t = store.task(taskId) ?: return@withLock null
        val until = clock() + minutes * MINUTE
        val state = ReminderStateTransitions.snooze(t.task.reminderState, until)
        val at = apply(t, state, settings())
        store.log(taskId, t.task.title, HistoryType.SNOOZED, formatDelay(minutes))
        at
    }

    /** Reporte l'échéance à demain (même heure si la tâche en a une). */
    suspend fun postponeToTomorrow(taskId: Long): Long? = mutex.withLock {
        presenter.dismiss(taskId)
        val t = store.task(taskId) ?: return@withLock null
        val z = zone()
        val tomorrow = Instant.ofEpochMilli(clock()).atZone(z).toLocalDate().plusDays(1)
        val due = t.task.dueAt
        val newDue = if (due != null && t.task.hasTime) {
            val time = Instant.ofEpochMilli(due).atZone(z).toLocalTime()
            tomorrow.atTime(time).atZone(z).toInstant().toEpochMilli()
        } else tomorrow.atStartOfDay(z).toInstant().toEpochMilli()
        store.moveDue(taskId, newDue, due != null && t.task.hasTime)
        store.log(taskId, t.task.title, HistoryType.SNOOZED, "Reportée à demain")
        rescheduleLocked(taskId)
    }

    suspend fun disableReminders(taskId: Long): Unit = mutex.withLock {
        scheduler.cancel(taskId)
        presenter.dismiss(taskId)
        val t = store.task(taskId) ?: return@withLock
        store.setRemindersEnabled(taskId, false)
        store.saveState(taskId, ReminderStateTransitions.reset(), null)
        store.log(taskId, t.task.title, HistoryType.REMINDERS_DISABLED)
    }

    fun cancel(taskId: Long) {
        scheduler.cancel(taskId)
        presenter.dismiss(taskId)
    }

    private fun formatDelay(minutes: Int): String = when {
        minutes < 60 -> "+$minutes min"
        minutes % 60 == 0 -> "+${minutes / 60} h"
        else -> "+${minutes / 60} h ${minutes % 60} min"
    }
}
