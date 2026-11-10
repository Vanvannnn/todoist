package com.brr.app.reminder

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import com.brr.app.data.prefs.SettingsRepository
import com.brr.app.data.repository.PunchlineRepository
import com.brr.app.domain.TriggerKind
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.ui.alarm.AlarmActivity
import kotlin.random.Random

/** Colle Android : transforme un déclenchement d'alarme en notification/écran immersif avec punchline. */
class ReminderEngine(
    private val context: Context,
    private val coordinator: ReminderCoordinator,
    private val settings: SettingsRepository,
    private val punchlines: PunchlineRepository,
    private val notifier: ReminderNotifier,
) {
    suspend fun onAlarm(taskId: Long) {
        val outcome = coordinator.handleFire(taskId) ?: return
        val s = settings.current()
        val task = outcome.task.task
        val plan = outcome.plan
        val custom = task.customMessage
        val message = if (custom != null && (plan.level == ReminderLevel.NORMAL || Random.nextFloat() < 0.3f)) custom
        else punchlines.pick(plan.level, plan.isFinalWarning, task.title, plan.minutesLate, task.snoozeCount, s).text
        // Écrans immersifs : quelques phrases de plus, qui défilent pendant que l'alerte reste affichée.
        val extra = if (plan.level.ordinal >= ReminderLevel.HARCELEMENT.ordinal) List(2) {
            punchlines.pick(plan.level, plan.isFinalWarning, task.title, plan.minutesLate, task.snoozeCount, s).text
        } else emptyList()
        val content = ReminderContent(
            taskId = task.id, taskTitle = task.title, message = message, level = plan.level, kind = plan.kind,
            isFinal = plan.isFinalWarning, minutesLate = plan.minutesLate, nextAt = outcome.nextAt, dueAt = task.dueAt,
            messages = (listOf(message) + extra).distinct(), snoozeCount = task.snoozeCount,
        )
        notifier.show(content, s)
        if (isForeground() && plan.level.ordinal >= ReminderLevel.INSISTANT.ordinal && plan.kind != TriggerKind.PRE_REMINDER) {
            InAppAlerts.emit(InAppAlert(task.id, NotificationTitle.of(content), message, plan.level))
        }
    }

    /** Aperçu depuis les réglages : une fausse tâche, de vraies notifications. */
    suspend fun preview(level: ReminderLevel, launchImmersive: Boolean) {
        val s = settings.current()
        val title = SAMPLE_TASKS.random()
        val msgs = List(3) { punchlines.pick(level, false, title, 37, 2, s).text }.distinct()
        val msg = msgs.first()
        val c = ReminderContent(
            taskId = PREVIEW_ID - level.ordinal, taskTitle = title, message = msg, level = level, kind = TriggerKind.ESCALATION,
            isFinal = false, minutesLate = 37, nextAt = System.currentTimeMillis() + 5 * 60_000, dueAt = null, preview = true,
            messages = msgs, snoozeCount = 2,
        )
        notifier.show(c, s)
        if (launchImmersive && level.ordinal >= ReminderLevel.HARCELEMENT.ordinal) {
            context.startActivity(AlarmActivity.intent(context, c).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun isForeground(): Boolean =
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)

    companion object {
        const val PREVIEW_ID = -10L
        val SAMPLE_TASKS = listOf("Sortir les poubelles", "Faire la vaisselle", "Appeler le dentiste", "Plier le linge")
    }
}

object NotificationTitle {
    fun of(c: ReminderContent) = ReminderNotifier.title(c)
}
