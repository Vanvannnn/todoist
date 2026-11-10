package com.brr.app.reminder

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.brr.app.BrrApplication
import java.util.concurrent.TimeUnit

/**
 * Filet de sécurité : vérifie périodiquement que chaque tâche a bien son alarme programmée
 * (certains constructeurs suppriment les alarmes des applis « endormies ») et répare si besoin.
 */
class ReminderVerifyWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result = try {
        val c = (applicationContext as BrrApplication).container
        // Calendriers d'abord (cours déplacés, annulés…), puis recalcul de toutes les alarmes avec les nouvelles pauses.
        runCatching { c.calendars.sync() }
        c.reminderCoordinator.rescheduleAll()
        Result.success()
    } catch (e: Exception) {
        Result.retry()
    }

    companion object {
        private const val NAME = "brr_verify_reminders"
        fun enqueue(context: Context) {
            val req = PeriodicWorkRequestBuilder<ReminderVerifyWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
