package com.brr.app.reminder

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import com.brr.app.data.prefs.AppSettings
import com.brr.app.domain.model.ReminderLevel

/**
 * Un canal par niveau. Le son et la vibration d'un canal étant figés à sa création, l'identifiant
 * contient une empreinte de la configuration : quand l'utilisateur change de son, un nouveau canal
 * est créé et l'ancien supprimé. Le système (mode silencieux, vibreur, Ne pas déranger) garde la main.
 */
object NotificationChannels {
    private const val PREFIX = "brr_lvl"

    fun vibrationPattern(level: ReminderLevel, strength: Float): LongArray {
        val base = when (level) {
            ReminderLevel.NORMAL -> longArrayOf(0, 180)
            ReminderLevel.INSISTANT -> longArrayOf(0, 300, 150, 300)
            ReminderLevel.HARCELEMENT -> longArrayOf(0, 500, 200, 500, 200, 500)
            ReminderLevel.BRR -> longArrayOf(0, 800, 200, 800, 200, 800, 200, 1200)
        }
        return base.mapIndexed { i, v -> if (i % 2 == 1) (v * strength).toLong().coerceIn(50, 3000) else v }.toLongArray()
    }

    fun channelId(level: ReminderLevel, s: AppSettings): String {
        val sig = "${s.soundFor(level)}|${s.vibrationEnabled}|${"%.2f".format(java.util.Locale.US, s.vibrationStrength)}"
        return "${PREFIX}${level.ordinal}_${Integer.toHexString(sig.hashCode())}"
    }

    fun ensure(context: Context, s: AppSettings) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val wanted = ReminderLevel.entries.associateBy { channelId(it, s) }
        nm.notificationChannels.filter { it.id.startsWith(PREFIX) && it.id !in wanted }.forEach { nm.deleteNotificationChannel(it.id) }
        for ((id, level) in wanted) {
            if (nm.getNotificationChannel(id) != null) continue
            val ch = NotificationChannel(id, name(level), importance(level)).apply {
                description = level.tagline
                val uri = AlarmSounds.uri(context, s.soundFor(level))
                // USAGE_NOTIFICATION : le son suit le mode silencieux/vibreur du téléphone.
                setSound(uri, AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                enableVibration(s.vibrationEnabled)
                if (s.vibrationEnabled) vibrationPattern = vibrationPattern(level, s.vibrationStrength)
                enableLights(true)
                lightColor = Color.RED
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(ch)
        }
    }

    fun name(level: ReminderLevel) = "Rappels — ${level.label}"

    private fun importance(level: ReminderLevel) =
        if (level == ReminderLevel.NORMAL) NotificationManager.IMPORTANCE_DEFAULT else NotificationManager.IMPORTANCE_HIGH
}
