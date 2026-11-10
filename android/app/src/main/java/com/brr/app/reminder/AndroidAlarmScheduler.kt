package com.brr.app.reminder

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import com.brr.app.MainActivity
import com.brr.app.domain.model.ReminderLevel

class AndroidAlarmScheduler(private val context: Context) : AlarmScheduler {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    override fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager.canScheduleExactAlarms()

    override fun schedule(taskId: Long, triggerAt: Long, level: ReminderLevel) {
        val pi = firePendingIntent(taskId, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (canScheduleExact()) {
                if (level.ordinal >= ReminderLevel.HARCELEMENT.ordinal) {
                    // setAlarmClock : le mécanisme le plus fiable (non différé par Doze), affiche l'icône d'alarme.
                    val show = PendingIntent.getActivity(
                        context, taskId.toInt(), MainActivity.taskIntent(context, taskId),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                    )
                    alarmManager.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
                } else {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
                }
            } else {
                // Sans autorisation d'alarme exacte, Android peut décaler le rappel de quelques minutes.
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Alarme exacte refusée, repli sur une alarme inexacte", e)
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    override fun cancel(taskId: Long) {
        firePendingIntent(taskId, PendingIntent.FLAG_NO_CREATE)?.let {
            alarmManager.cancel(it)
            it.cancel()
        }
    }

    override fun isScheduled(taskId: Long): Boolean = firePendingIntent(taskId, PendingIntent.FLAG_NO_CREATE) != null

    private fun firePendingIntent(taskId: Long, flag: Int): PendingIntent? {
        val intent = Intent(context, ReminderReceiver::class.java)
            .setAction(ReminderReceiver.ACTION_FIRE)
            .setData(Uri.parse("brr://task/$taskId"))
            .putExtra(Extras.TASK_ID, taskId)
        return PendingIntent.getBroadcast(context, taskId.toInt(), intent, flag or PendingIntent.FLAG_IMMUTABLE)
    }

    private companion object { const val TAG = "BrrAlarm" }
}

object Extras {
    const val TASK_ID = "task_id"
    const val LEVEL = "level"
    const val PUNCHLINE = "punchline"
    const val FINAL = "final"
    const val NEXT_AT = "next_at"
    const val MINUTES_LATE = "minutes_late"
    const val SNOOZE_MINUTES = "snooze_minutes"
    const val PREVIEW = "preview"
    const val TITLE = "title"
}
