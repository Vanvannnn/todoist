package com.brr.app

import android.content.Context
import com.brr.app.calendar.CalendarRepository
import com.brr.app.data.db.BrrDatabase
import com.brr.app.data.prefs.SettingsRepository
import com.brr.app.data.repository.ProjectRepository
import com.brr.app.data.repository.PunchlineRepository
import com.brr.app.data.repository.TaskManager
import com.brr.app.data.repository.TaskRepository
import com.brr.app.reminder.AndroidAlarmScheduler
import com.brr.app.reminder.PermissionCenter
import com.brr.app.reminder.ReminderCoordinator
import com.brr.app.reminder.ReminderEngine
import com.brr.app.reminder.ReminderNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Injection de dépendances manuelle : un seul graphe d'objets pour toute l'application. */
class AppContainer(context: Context) {
    val app: Context = context.applicationContext

    /** Portée qui survit aux écrans (actions lancées depuis l'écran d'alarme, receivers…). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: BrrDatabase by lazy { BrrDatabase.build(app) }
    val settings: SettingsRepository by lazy { SettingsRepository(app) }
    val tasks: TaskRepository by lazy { TaskRepository(database) }
    val projects: ProjectRepository by lazy { ProjectRepository(database) }
    val punchlines: PunchlineRepository by lazy { PunchlineRepository(database, settings) }
    val calendars: CalendarRepository by lazy { CalendarRepository(app, database, settings) }

    val scheduler: AndroidAlarmScheduler by lazy { AndroidAlarmScheduler(app) }
    val notifier: ReminderNotifier by lazy { ReminderNotifier(app) }
    val reminderCoordinator: ReminderCoordinator by lazy {
        ReminderCoordinator(tasks, scheduler, notifier, { settings.current().reminderSettings(calendars.pauseWindows()) })
    }
    val reminderEngine: ReminderEngine by lazy { ReminderEngine(app, reminderCoordinator, settings, punchlines, notifier) }
    val taskManager: TaskManager by lazy { TaskManager(tasks, projects, reminderCoordinator, settings) }
    val permissions: PermissionCenter by lazy { PermissionCenter(app, scheduler) }
}
