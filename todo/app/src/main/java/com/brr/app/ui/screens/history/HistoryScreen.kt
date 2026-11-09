package com.brr.app.ui.screens.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.brr.app.AppContainer
import com.brr.app.data.db.HistoryEntity
import com.brr.app.data.db.TaskWithDetails
import com.brr.app.domain.model.HistoryType
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.components.ChoiceChip
import com.brr.app.ui.components.EmptyState
import com.brr.app.ui.components.Fmt
import com.brr.app.ui.components.SectionHeader
import com.brr.app.ui.components.TaskRow
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(private val c: AppContainer) : ViewModel() {
    val completed: StateFlow<List<TaskWithDetails>?> = c.tasks.observeCompleted().map { it as List<TaskWithDetails>? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val journal: StateFlow<List<HistoryEntity>?> = c.tasks.observeHistory().map { it as List<HistoryEntity>? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun reopen(id: Long) = viewModelScope.launch { c.taskManager.reopen(id) }
    fun clear() = viewModelScope.launch { c.tasks.clearHistory() }
}

@Composable
fun HistoryScreen(container: AppContainer, onBack: () -> Unit, onOpenTask: (Long) -> Unit) {
    val vm = brrViewModel { HistoryViewModel(container) }
    val completed by vm.completed.collectAsStateWithLifecycle()
    val journal by vm.journal.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = 48.dp)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                    Spacer(Modifier.weight(1f))
                    if (tab == 1 && !journal.isNullOrEmpty()) TextButton(onClick = { confirmClear = true }) { Text("Effacer le journal") }
                }
                Text("Historique", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 20.dp))
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Terminées", tab == 0, { tab = 0 })
                    ChoiceChip("Journal", tab == 1, { tab = 1 })
                }
            }
        }
        if (tab == 0) {
            val list = completed ?: emptyList()
            if (completed != null && list.isEmpty()) item { EmptyState("Rien de terminé", "Pour l'instant. La première fois est toujours la plus dure.") }
            val groups = list.groupBy { Fmt.day(it.task.completedAt ?: it.task.updatedAt) }
            groups.forEach { (day, items) ->
                item(key = "d_$day") { SectionHeader(day, items.size) }
                items(items, key = { it.task.id }) { t ->
                    TaskRow(t, onToggle = { vm.reopen(t.task.id) }, onClick = { onOpenTask(t.task.id) })
                }
            }
        } else {
            val list = journal ?: emptyList()
            if (journal != null && list.isEmpty()) item { EmptyState("Journal vide", "Il se remplira à mesure que tu agis (ou que tu reportes).", BrrIcons.History) }
            val groups = list.groupBy { Fmt.day(it.at) }
            groups.forEach { (day, items) ->
                item(key = "j_$day") { SectionHeader(day, items.size) }
                items(items, key = { "h${it.id}" }) { h -> JournalRow(h) { h.taskId?.let(onOpenTask) } }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Effacer le journal ?") },
            text = { Text("Les tâches ne sont pas touchées, seul le journal d'activité est vidé.") },
            confirmButton = { TextButton(onClick = { vm.clear(); confirmClear = false }) { Text("Effacer", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun JournalRow(h: HistoryEntity, onClick: () -> Unit) {
    val (icon, color) = iconFor(h.type)
    Row(
        Modifier.fillMaxWidth().then(if (h.taskId != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(h.taskTitle, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(h.type.label + if (h.detail.isNotBlank()) " · ${h.detail}" else "", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
        }
        Text(Fmt.time(h.at), style = MaterialTheme.typography.labelMedium, color = Brr.colors.muted)
    }
}

@Composable
private fun iconFor(t: HistoryType): Pair<ImageVector, Color> = when (t) {
    HistoryType.CREATED -> Icons.Default.Add to MaterialTheme.colorScheme.onSurfaceVariant
    HistoryType.COMPLETED -> Icons.Default.Check to Brr.colors.success
    HistoryType.REOPENED -> Icons.Default.Refresh to BrrPalette.Blue
    HistoryType.SNOOZED -> BrrIcons.Alarm to BrrPalette.Amber
    HistoryType.REMINDER_FIRED -> Icons.Default.Notifications to BrrPalette.Orange
    HistoryType.REMINDERS_DISABLED -> BrrIcons.Stop to Brr.colors.muted
    HistoryType.DELETED -> Icons.Default.Delete to BrrPalette.Red
}
