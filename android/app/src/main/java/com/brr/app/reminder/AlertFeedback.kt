package com.brr.app.reminder

import android.app.NotificationManager
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import com.brr.app.data.prefs.AppSettings
import com.brr.app.domain.model.ReminderLevel

/** Ce que le téléphone autorise actuellement. Bullylist ne contourne jamais ces réglages. */
data class DeviceAlertPolicy(val soundAllowed: Boolean, val vibrationAllowed: Boolean, val reason: String) {
    companion object {
        fun read(context: Context): DeviceAlertPolicy {
            val am = context.getSystemService(AudioManager::class.java)
            val nm = context.getSystemService(NotificationManager::class.java)
            val filter = nm.currentInterruptionFilter
            val dndActive = filter != NotificationManager.INTERRUPTION_FILTER_ALL &&
                filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
            return when {
                dndActive -> DeviceAlertPolicy(false, false, "Ne pas déranger est actif : sons et vibrations de l'écran coupés.")
                am.ringerMode == AudioManager.RINGER_MODE_SILENT -> DeviceAlertPolicy(false, false, "Téléphone en silencieux.")
                am.ringerMode == AudioManager.RINGER_MODE_VIBRATE -> DeviceAlertPolicy(false, true, "Téléphone en vibreur : vibrations uniquement.")
                else -> DeviceAlertPolicy(true, true, "Son et vibrations autorisés.")
            }
        }
    }
}

/**
 * Son et vibration de l'écran immersif, joués uniquement si le téléphone l'autorise
 * et si le canal de notification du niveau n'a pas été rendu muet par l'utilisateur.
 */
class AlertFeedback(private val context: Context) {
    private var player: MediaPlayer? = null
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    else @Suppress("DEPRECATION") context.getSystemService(Vibrator::class.java)

    fun start(level: ReminderLevel, s: AppSettings) {
        stop()
        val policy = DeviceAlertPolicy.read(context)
        val channel = context.getSystemService(NotificationManager::class.java)
            .getNotificationChannel(NotificationChannels.channelId(level, s))
        val channelMuted = channel != null && (channel.importance == NotificationManager.IMPORTANCE_NONE)
        if (policy.soundAllowed && !channelMuted && channel?.sound != null) {
            val uri = AlarmSounds.uri(context, s.soundFor(level))
            if (uri != null) playLoop(uri, s.volume)
        }
        if (policy.vibrationAllowed && s.vibrationEnabled && !channelMuted && vibrator?.hasVibrator() == true) {
            val pattern = NotificationChannels.vibrationPattern(level, s.vibrationStrength)
            val loop = pattern + longArrayOf(1500)
            vibrator.vibrate(VibrationEffect.createWaveform(loop, if (level == ReminderLevel.BRR) 0 else -1))
        }
    }

    fun preview(soundId: String, volume: Float) {
        stop()
        val policy = DeviceAlertPolicy.read(context)
        if (!policy.soundAllowed) return
        AlarmSounds.uri(context, soundId)?.let { playLoop(it, volume, loop = false) }
    }

    private fun playLoop(uri: android.net.Uri, volume: Float, loop: Boolean = true) {
        try {
            player = MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                setDataSource(context, uri)
                isLooping = loop
                setVolume(volume, volume)
                setOnPreparedListener { it.start() }
                prepareAsync()
            }
        } catch (e: Exception) {
            Log.w("BrrFeedback", "Lecture du son impossible", e)
            player = null
        }
    }

    fun stop() {
        player?.runCatching { if (isPlaying) stop(); release() }
        player = null
        vibrator?.cancel()
    }
}
