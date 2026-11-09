package com.brr.app.reminder

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.brr.app.MainActivity
import com.brr.app.R
import com.brr.app.data.prefs.AppSettings
import com.brr.app.domain.TriggerKind
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.ui.alarm.AlarmActivity
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ReminderContent(
    val taskId: Long,
    val taskTitle: String,
    val message: String,
    val level: ReminderLevel,
    val kind: TriggerKind,
    val isFinal: Boolean,
    val minutesLate: Long,
    val nextAt: Long?,
    val dueAt: Long?,
    val preview: Boolean = false,
)

class ReminderNotifier(private val context: Context) : ReminderPresenter {
    private val nm = NotificationManagerCompat.from(context)

    fun notificationsAllowed(): Boolean = nm.areNotificationsEnabled()

    /** Affiche le rappel. Retourne false si les notifications sont bloquées par l'utilisateur. */
    @SuppressLint("MissingPermission") // areNotificationsEnabled() est vérifié juste avant.
    fun show(c: ReminderContent, s: AppSettings, silent: Boolean = false): Boolean {
        NotificationChannels.ensure(context, s)
        if (!nm.areNotificationsEnabled()) return false
        val n = build(c, s, silent)
        nm.notify(TAG, c.taskId.toInt(), n)
        return true
    }

    /**
     * Quand l'écran immersif est affiché, il prend le relais du son : on remplace la notification
     * (annulation = arrêt immédiat du son « insistant ») par une version silencieuse, toujours persistante.
     */
    @SuppressLint("MissingPermission")
    fun silence(c: ReminderContent, s: AppSettings) {
        if (!nm.areNotificationsEnabled()) return
        nm.cancel(TAG, c.taskId.toInt())
        nm.notify(TAG, c.taskId.toInt(), build(c, s, silent = true))
    }

    override fun dismiss(taskId: Long) = nm.cancel(TAG, taskId.toInt())

    private fun build(c: ReminderContent, s: AppSettings, silent: Boolean): Notification {
        val level = c.level
        val b = NotificationCompat.Builder(context, NotificationChannels.channelId(level, s))
            .setSmallIcon(R.drawable.ic_stat_brr)
            .setColor(0xFFFF2D3D.toInt())
            .setContentTitle(title(c))
            .setContentText(c.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body(c)))
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(false)
            .setSilent(silent)
            .setCategory(if (level.ordinal >= ReminderLevel.HARCELEMENT.ordinal) NotificationCompat.CATEGORY_ALARM else NotificationCompat.CATEGORY_REMINDER)
            .setPriority(when (level) {
                ReminderLevel.NORMAL -> NotificationCompat.PRIORITY_DEFAULT
                ReminderLevel.INSISTANT -> NotificationCompat.PRIORITY_HIGH
                else -> NotificationCompat.PRIORITY_MAX
            })
            .setAutoCancel(level != ReminderLevel.BRR)
            .setOngoing(level == ReminderLevel.BRR && !c.preview)

        val immersive = level.ordinal >= ReminderLevel.HARCELEMENT.ordinal && c.kind != TriggerKind.PRE_REMINDER
        if (immersive) {
            val full = PendingIntent.getActivity(
                context, c.taskId.toInt(), AlarmActivity.intent(context, c),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.setContentIntent(full)
            // Écran plein écran : Android décide (autorisation « notifications plein écran » sur Android 14+).
            if (!silent) b.setFullScreenIntent(full, true)
        } else {
            val open = PendingIntent.getActivity(
                context, c.taskId.toInt(), MainActivity.taskIntent(context, c.taskId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            b.setContentIntent(open)
        }

        b.addAction(0, if (immersive) "C'EST FAIT" else "Fait", action(c.taskId, ReminderActionReceiver.ACTION_DONE))
        when {
            c.kind == TriggerKind.PRE_REMINDER -> Unit
            level == ReminderLevel.NORMAL -> {
                b.addAction(0, "+15 min", action(c.taskId, ReminderActionReceiver.ACTION_SNOOZE, 15))
                b.addAction(0, "+1 h", action(c.taskId, ReminderActionReceiver.ACTION_SNOOZE, 60))
            }
            level == ReminderLevel.INSISTANT -> {
                b.addAction(0, "+5 min", action(c.taskId, ReminderActionReceiver.ACTION_SNOOZE, 5))
                b.addAction(0, "+15 min", action(c.taskId, ReminderActionReceiver.ACTION_SNOOZE, 15))
            }
            else -> {
                b.addAction(0, "5 MIN, PROMIS", action(c.taskId, ReminderActionReceiver.ACTION_SNOOZE, 5))
                b.addAction(0, "Demain", action(c.taskId, ReminderActionReceiver.ACTION_TOMORROW))
            }
        }
        val n = b.build()
        // Son répété jusqu'à action : uniquement en mode B.R.R. (et toujours soumis aux réglages du téléphone).
        if (level == ReminderLevel.BRR && !silent) n.flags = n.flags or Notification.FLAG_INSISTENT
        return n
    }

    private fun action(taskId: Long, action: String, minutes: Int = 0): PendingIntent {
        val intent = Intent(context, ReminderActionReceiver::class.java)
            .setAction(action)
            .setData(Uri.parse("brr://action/$taskId/$action/$minutes"))
            .putExtra(Extras.TASK_ID, taskId)
            .putExtra(Extras.SNOOZE_MINUTES, minutes)
        return PendingIntent.getBroadcast(context, (taskId * 31 + action.hashCode() + minutes).toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val TAG = "brr_reminder"
        private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

        fun title(c: ReminderContent): String = when {
            c.kind == TriggerKind.PRE_REMINDER -> "Bientôt : ${c.taskTitle}"
            c.isFinal -> "DERNIER AVERTISSEMENT : ${c.taskTitle.uppercase()}"
            c.level == ReminderLevel.NORMAL -> c.taskTitle
            c.level == ReminderLevel.INSISTANT -> "Toujours pas fait : ${c.taskTitle}"
            c.level == ReminderLevel.HARCELEMENT -> "MISSION EN COURS : ${c.taskTitle.uppercase()}"
            else -> "🚨 B.R.R. — ${c.taskTitle.uppercase()}"
        }

        fun body(c: ReminderContent): String {
            val prefix = if (c.kind == TriggerKind.PRE_REMINDER && c.dueAt != null)
                "Échéance à ${timeFmt.format(Instant.ofEpochMilli(c.dueAt).atZone(ZoneId.systemDefault()))}. " else ""
            return prefix + c.message
        }
    }
}
