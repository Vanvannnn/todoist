package com.brr.app.reminder

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.brr.app.BrrApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Exécute un traitement suspendu depuis un BroadcastReceiver sans bloquer le thread principal. */
private fun BroadcastReceiver.runAsync(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        try {
            block()
        } catch (e: Exception) {
            Log.e("BrrReceiver", "Erreur pendant le traitement du rappel", e)
        } finally {
            pending.finish()
        }
    }
}

/** Reçoit les alarmes programmées. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_FIRE) return
        val taskId = intent.getLongExtra(Extras.TASK_ID, 0L)
        if (taskId <= 0L) return
        val container = (context.applicationContext as BrrApplication).container
        runAsync { container.reminderEngine.onAlarm(taskId) }
    }

    companion object { const val ACTION_FIRE = "com.brr.app.action.FIRE" }
}

/** Boutons d'action des notifications. */
class ReminderActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(Extras.TASK_ID, 0L)
        val container = (context.applicationContext as BrrApplication).container
        if (taskId <= 0L) { container.notifier.dismiss(taskId); return } // aperçu
        val minutes = intent.getIntExtra(Extras.SNOOZE_MINUTES, 0)
        runAsync {
            val tm = container.taskManager
            when (intent.action) {
                ACTION_DONE -> tm.complete(taskId)
                ACTION_SNOOZE -> tm.snooze(taskId, minutes.coerceAtLeast(1))
                ACTION_TOMORROW -> tm.postponeToTomorrow(taskId)
                ACTION_DISABLE -> tm.disableReminders(taskId)
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.brr.app.action.DONE"
        const val ACTION_SNOOZE = "com.brr.app.action.SNOOZE"
        const val ACTION_TOMORROW = "com.brr.app.action.TOMORROW"
        const val ACTION_DISABLE = "com.brr.app.action.DISABLE"
    }
}

/** Redémarrage, mise à jour de l'appli, changement d'heure/fuseau, autorisation d'alarme exacte : on reprogramme tout. */
class SystemEventsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val accepted = setOf(
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED, AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        if (intent.action !in accepted) return
        val container = (context.applicationContext as BrrApplication).container
        runAsync { container.reminderCoordinator.rescheduleAll() }
    }
}
