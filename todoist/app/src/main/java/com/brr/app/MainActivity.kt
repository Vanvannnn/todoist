package com.brr.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brr.app.data.prefs.AppSettings
import com.brr.app.ui.BrrRoot
import com.brr.app.ui.theme.BrrTheme

class MainActivity : ComponentActivity() {
    /** Tâche à ouvrir suite au tap sur une notification. */
    private var pendingTaskId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        pendingTaskId = intent.taskIdExtra()
        val container = (application as BrrApplication).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val s: AppSettings? = settings
            BrrTheme(mode = s?.themeMode) {
                BrrRoot(
                    container = container,
                    settings = s,
                    openTaskId = pendingTaskId,
                    onTaskOpened = { pendingTaskId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.taskIdExtra()?.let { pendingTaskId = it }
    }

    private fun Intent.taskIdExtra(): Long? = getLongExtra(EXTRA_OPEN_TASK, 0L).takeIf { it > 0 }

    companion object {
        const val EXTRA_OPEN_TASK = "open_task"
        fun taskIntent(context: Context, taskId: Long): Intent = Intent(context, MainActivity::class.java)
            .putExtra(EXTRA_OPEN_TASK, taskId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
