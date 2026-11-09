package com.brr.app.ui.alarm

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.brr.app.BrrApplication
import com.brr.app.domain.TriggerKind
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.ThemeMode
import com.brr.app.reminder.AlertFeedback
import com.brr.app.reminder.Extras
import com.brr.app.reminder.ReminderContent
import com.brr.app.ui.theme.BrrTheme
import kotlinx.coroutines.launch

/** Écran immersif des niveaux 3 et 4, affiché via l'intent plein écran autorisé par Android. */
class AlarmActivity : ComponentActivity() {
    private val content = mutableStateOf<ReminderContent?>(null)
    private lateinit var feedback: AlertFeedback
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        feedback = AlertFeedback(applicationContext)
        load(intent)
        setContent {
            BrrTheme(ThemeMode.DARK) {
                content.value?.let { c ->
                    AlarmScreen(
                        content = c,
                        onDone = { act { it.complete(c.taskId) } },
                        onSnooze = { m -> act { it.snooze(c.taskId, m) } },
                        onTomorrow = { act { it.postponeToTomorrow(c.taskId) } },
                        onDisable = { act { it.disableReminders(c.taskId) } },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        load(intent)
    }

    private fun load(intent: Intent) {
        val c = read(intent) ?: run { finish(); return }
        content.value = c
        handled = false
        val container = (application as BrrApplication).container
        lifecycleScope.launch {
            val s = container.settings.current()
            // L'écran prend le relais du son de la notification (en respectant silencieux / Ne pas déranger).
            if (!c.preview) container.notifier.silence(c, s)
            feedback.start(c.level, s)
        }
    }

    private fun act(block: suspend (com.brr.app.data.repository.TaskManager) -> Unit) {
        if (handled) return
        handled = true
        feedback.stop()
        val c = content.value
        val container = (application as BrrApplication).container
        if (c != null && c.taskId > 0) container.appScope.launch { block(container.taskManager) }
        else if (c != null) container.notifier.dismiss(c.taskId)
        finish()
    }

    override fun onStop() {
        super.onStop()
        feedback.stop()
    }

    override fun onDestroy() {
        feedback.stop()
        super.onDestroy()
    }

    companion object {
        fun intent(context: Context, c: ReminderContent): Intent = Intent(context, AlarmActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
            .putExtra(Extras.TASK_ID, c.taskId)
            .putExtra(Extras.TITLE, c.taskTitle)
            .putExtra(Extras.PUNCHLINE, c.message)
            .putExtra(Extras.LEVEL, c.level.name)
            .putExtra("kind", c.kind.name)
            .putExtra(Extras.FINAL, c.isFinal)
            .putExtra(Extras.MINUTES_LATE, c.minutesLate)
            .putExtra(Extras.NEXT_AT, c.nextAt ?: -1L)
            .putExtra("due_at", c.dueAt ?: -1L)
            .putExtra(Extras.PREVIEW, c.preview)

        private fun read(i: Intent): ReminderContent? {
            val id = i.getLongExtra(Extras.TASK_ID, 0L)
            if (id == 0L) return null
            return ReminderContent(
                taskId = id,
                taskTitle = i.getStringExtra(Extras.TITLE) ?: return null,
                message = i.getStringExtra(Extras.PUNCHLINE) ?: "",
                level = ReminderLevel.entries.firstOrNull { it.name == i.getStringExtra(Extras.LEVEL) } ?: ReminderLevel.HARCELEMENT,
                kind = TriggerKind.entries.firstOrNull { it.name == i.getStringExtra("kind") } ?: TriggerKind.ESCALATION,
                isFinal = i.getBooleanExtra(Extras.FINAL, false),
                minutesLate = i.getLongExtra(Extras.MINUTES_LATE, 0),
                nextAt = i.getLongExtra(Extras.NEXT_AT, -1L).takeIf { it > 0 },
                dueAt = i.getLongExtra("due_at", -1L).takeIf { it > 0 },
                preview = i.getBooleanExtra(Extras.PREVIEW, false),
            )
        }
    }
}
