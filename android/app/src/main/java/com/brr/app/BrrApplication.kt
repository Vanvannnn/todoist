package com.brr.app

import android.app.Application
import android.util.Log
import com.brr.app.reminder.NotificationChannels
import com.brr.app.reminder.ReminderVerifyWorker
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class BrrApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Les canaux suivent les réglages de son et de vibration.
        container.appScope.launch {
            container.settings.settings
                .map { s -> Triple(s.sounds, s.vibrationEnabled, s.vibrationStrength) to s }
                .distinctUntilChanged { a, b -> a.first == b.first }
                .collect { (_, s) -> runCatching { NotificationChannels.ensure(this@BrrApplication, s) } }
        }
        // Vérification au démarrage du processus + vérification périodique.
        container.appScope.launch {
            runCatching { container.reminderCoordinator.rescheduleAll() }
                .onFailure { Log.e("BrrApp", "Reprogrammation impossible", it) }
        }
        ReminderVerifyWorker.enqueue(this)
    }
}
