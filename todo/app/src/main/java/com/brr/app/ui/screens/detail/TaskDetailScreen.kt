package com.brr.app.ui.screens.detail

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.brr.app.AppContainer
import com.brr.app.data.db.HistoryEntity
import com.brr.app.data.db.SubtaskEntity
import com.brr.app.data.db.TaskWithDetails
import com.brr.app.domain.RecurrenceCalculator
import com.brr.app.domain.model.RepeatType
import com.brr.app.ui.components.BrrCard
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.components.CheckCircle
import com.brr.app.ui.components.ChoiceChip
import com.brr.app.ui.components.Fmt
import com.brr.app.ui.components.LevelBadge
import com.brr.app.ui.components.Pill
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class DetailState(val loading: Boolean = true, val item: TaskWithDetails? = null, val history: List<HistoryEntity> = emptyList())

class TaskDetailViewModel(private val c: AppContainer, private val id: Long) : ViewModel() {
    val state: StateFlow<DetailState> = combine(c.tasks.observe(id), c.tasks.observeTaskHistory(id)) { t, h -> DetailState(false, t, h) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailState())

    fun toggleDone() = viewModelScope.launch {
        val t = state.value.item?.task ?: return@launch
        if (t.completed) c.taskManager.reopen(id) else c.taskManager.complete(id)
    }
    fun snooze(min: Int) = viewModelScope.launch { c.taskManager.snooze(id, min) }
    fun tomorrow() = viewModelScope.launch { c.taskManager.postponeToTomorrow(id) }
    fun toggleReminders() = viewModelScope.launch {
        val t = state.value.item?.task ?: return@launch
        if (t.remindersEnabled) c.taskManager.disableReminders(id) else c.taskManager.enableReminders(id)
    }
    fun toggleSub(s: SubtaskEntity) = viewModelScope.launch { c.taskManager.toggleSubtask(s) }
    fun addSub(title: String) = viewModelScope.launch { c.taskManager.addSubtask(id, title, state.value.item?.subtasks?.size ?: 0) }
    fun deleteSub(s: SubtaskEntity) = viewModelScope.launch { c.taskManager.deleteSubtask(s) }
    fun delete(done: () -> Unit) = viewModelScope.launch { c.taskManager.delete(id); done() }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TaskDetailScreen(container: AppContainer, taskId: Long, onBack: () -> Unit, onEdit: (Long) -> Unit) {
    val vm = brrViewModel(key = "detail_$taskId") { TaskDetailViewModel(container, taskId) }
    val st by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
            Spacer(Modifier.weight(1f))
            if (st.item != null) {
                IconButton(onClick = { onEdit(taskId) }) { Icon(Icons.Default.Edit, "Modifier") }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Supprimer") }
            }
        }
        val item = st.item
        when {
            st.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            item == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Cette tâche a disparu. Peut-être qu'elle s'est faite toute seule ?", color = Brr.colors.muted, modifier = Modifier.padding(32.dp))
            }
            else -> DetailContent(item, st.history, vm)
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Supprimer la tâche ?") },
            text = { Text("Ses rappels seront annulés. Supprimer une tâche ne compte pas comme la faire, on se comprend.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; vm.delete(onBack) }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Annuler") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DetailContent(item: TaskWithDetails, history: List<HistoryEntity>, vm: TaskDetailViewModel) {
    val t = item.task
    val now = System.currentTimeMillis()
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 2.dp)) { CheckCircle(t.completed, Brr.colors.priority(t.priority), vm::toggleDone, 26.dp) }
            Spacer(Modifier.width(4.dp))
            Text(t.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(top = 6.dp))
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            t.dueAt?.let { Pill(Fmt.due(it, t.hasTime), if (!t.completed && it < now) BrrPalette.Red else MaterialTheme.colorScheme.onSurfaceVariant, BrrIcons.Today, filled = true) }
            Pill(t.priority.label, Brr.colors.priority(t.priority), BrrIcons.Flag, filled = true)
            item.project?.let { Pill(it.name, Color(it.color), BrrIcons.Folder, filled = true) }
            if (t.repeatType != RepeatType.NONE) Pill(RecurrenceCalculator.describe(t.repeatType, t.repeatInterval, t.repeatDays), Brr.colors.muted, BrrIcons.Repeat, filled = true)
        }
        if (t.description.isNotBlank()) {
            Spacer(Modifier.height(18.dp))
            Text(t.description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // --- Rappels
        Spacer(Modifier.height(22.dp))
        BrrCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rappels", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                LevelBadge(t.reminderLevel)
            }
            Spacer(Modifier.height(8.dp))
            val status = when {
                t.completed -> "Tâche terminée : plus aucun rappel."
                !t.remindersEnabled -> "Rappels désactivés pour cette tâche."
                t.dueAt == null && t.snoozedUntil == null -> "Pas d'échéance, donc pas de rappel."
                t.snoozedUntil != null -> "Reportée jusqu'à ${Fmt.dateTime(t.snoozedUntil)} (${t.snoozeCount} report${if (t.snoozeCount > 1) "s" else ""})."
                t.nextReminderAt != null && t.escalationStep > 0 -> "Relance n°${t.escalationStep} prévue ${Fmt.relative(t.nextReminderAt)} (${Fmt.time(t.nextReminderAt)})."
                t.nextReminderAt != null -> "Prochain rappel : ${Fmt.dateTime(t.nextReminderAt)} (${Fmt.relative(t.nextReminderAt)})."
                else -> "Toutes les relances ont été envoyées. Ta conscience prend le relais."
            }
            Text(status, style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted)
            if (item.reminders.isNotEmpty()) {
                Text("Avant l'échéance : " + item.reminders.sortedByDescending { it.offsetMinutes }.joinToString { Fmt.minutes(it.offsetMinutes) },
                    style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted, modifier = Modifier.padding(top = 4.dp))
            }
            if (!t.completed) {
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("+5 min", false, { vm.snooze(5) }, icon = BrrIcons.Alarm)
                    ChoiceChip("+15 min", false, { vm.snooze(15) })
                    ChoiceChip("+1 h", false, { vm.snooze(60) })
                    ChoiceChip("Demain", false, vm::tomorrow)
                }
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = vm::toggleReminders) {
                    Text(if (t.remindersEnabled) "Désactiver les rappels de cette tâche" else "Réactiver les rappels")
                }
            }
        }

        // --- Sous-tâches
        Spacer(Modifier.height(22.dp))
        val done = item.subtasks.count { it.done }
        Text("Sous-tâches" + if (item.subtasks.isNotEmpty()) "  $done/${item.subtasks.size}" else "", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        item.sortedSubtasks.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                CheckCircle(s.done, Brr.colors.muted, { vm.toggleSub(s) }, 18.dp)
                Text(s.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f),
                    color = if (s.done) Brr.colors.muted else MaterialTheme.colorScheme.onBackground,
                    textDecoration = if (s.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null)
                IconButton(onClick = { vm.deleteSub(s) }) { Icon(Icons.Default.Close, "Supprimer", tint = Brr.colors.faint, modifier = Modifier.size(18.dp)) }
            }
        }
        var newSub by remember { mutableStateOf("") }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 9.dp))
            TextField(
                value = newSub, onValueChange = { newSub = it }, placeholder = { Text("Ajouter une sous-tâche") }, singleLine = true,
                modifier = Modifier.weight(1f),
                colors = TextFieldDefaults.colors(focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (newSub.isNotBlank()) { vm.addSub(newSub); newSub = "" } }),
            )
        }

        Spacer(Modifier.height(22.dp))
        if (t.completed) OutlinedButton(onClick = vm::toggleDone, modifier = Modifier.fillMaxWidth()) { Text("Rouvrir la tâche") }
        else Button(onClick = vm::toggleDone, modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Text("C'EST FAIT", style = MaterialTheme.typography.titleMedium) }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(26.dp))
            Text("Historique", style = MaterialTheme.typography.titleMedium)
            history.take(15).forEach { h ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Text(Fmt.dateTime(h.at), style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted, modifier = Modifier.width(140.dp))
                    Text(h.type.label + if (h.detail.isNotBlank()) " · ${h.detail}" else "", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}
