package com.brr.app.ui.screens.tasks

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.brr.app.data.db.ProjectEntity
import com.brr.app.domain.QuickAddParser
import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.components.ChoiceChip
import com.brr.app.ui.components.Fmt
import com.brr.app.ui.components.Pill
import com.brr.app.ui.theme.Brr
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickAddSheet(projectName: String?, onDismiss: () -> Unit, onSubmit: (String) -> Unit, onDetails: (String) -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf("") }
    var added by remember { mutableIntStateOf(0) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val parsed = remember(text) { if (text.isBlank()) null else QuickAddParser.parse(text, System.currentTimeMillis(), ZoneId.systemDefault()) }

    fun submit() {
        if (text.isBlank()) return
        onSubmit(text); text = ""; added++
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Brr.colors.card, dragHandle = null) {
        Column(Modifier.fillMaxWidth().padding(20.dp).imePadding().navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Nouvelle mission", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (added > 0) Text("$added ajoutée${if (added > 1) "s" else ""} ✓", style = MaterialTheme.typography.labelMedium, color = Brr.colors.success)
            }
            Spacer(Modifier.height(10.dp))
            TextField(
                value = text, onValueChange = { text = it },
                placeholder = { Text("Sortir les poubelles demain 20h !1 #Maison @brr", color = Brr.colors.faint) },
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                textStyle = MaterialTheme.typography.titleMedium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary, unfocusedIndicatorColor = Brr.colors.hairline,
                ),
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val p = parsed
                if (p?.dueAt != null) Pill(Fmt.due(p.dueAt, p.hasTime), MaterialTheme.colorScheme.primary, BrrIcons.Today, filled = true)
                if (p?.priority != null) Pill(p.priority.label, Brr.colors.priority(p.priority), BrrIcons.Flag, filled = true)
                (p?.projectName ?: projectName)?.let { Pill(it, Brr.colors.muted, BrrIcons.Folder, filled = true) }
                if (p?.level != null) Pill(p.level.label, Brr.colors.level(p.level), BrrIcons.Bolt, filled = true)
                if (p == null) Text(
                    "Astuces : « demain 18h », « lundi », « dans 2h », !1 à !4, #Projet, @brr",
                    style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted,
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { onDetails(text) }) { Text("Plus d'options") }
                Spacer(Modifier.weight(1f))
                Button(onClick = { submit() }, enabled = text.isNotBlank(), shape = MaterialTheme.shapes.medium,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) {
                    Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.padding(end = 6.dp)); Text("Ajouter")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun FilterSheet(
    filters: TaskFilters, projects: List<ProjectEntity>, showProjects: Boolean,
    onChange: (TaskFilters) -> Unit, onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Brr.colors.card) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp).navigationBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Filtres", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                if (filters.active) TextButton(onClick = { onChange(TaskFilters()) }) { Text("Réinitialiser") }
            }
            Label("Priorité")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.reversed().forEach { p ->
                    ChoiceChip(p.label, p in filters.priorities, {
                        onChange(filters.copy(priorities = filters.priorities.toggle(p)))
                    }, Brr.colors.priority(p), BrrIcons.Flag)
                }
            }
            Label("Niveau de rappel")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReminderLevel.entries.forEach { l ->
                    ChoiceChip(l.label, l in filters.levels, { onChange(filters.copy(levels = filters.levels.toggle(l))) }, Brr.colors.level(l))
                }
            }
            if (showProjects && projects.isNotEmpty()) {
                Label("Projets")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    projects.forEach { p ->
                        ChoiceChip(p.name, p.id in filters.projectIds, { onChange(filters.copy(projectIds = filters.projectIds.toggle(p.id))) }, Color(p.color), BrrIcons.Folder)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Uniquement les tâches datées", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(filters.onlyWithDate, { onChange(filters.copy(onlyWithDate = it)) })
            }
            Spacer(Modifier.width(1.dp))
        }
    }
}

@Composable
private fun Label(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brr.colors.muted, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

private fun <T> Set<T>.toggle(v: T): Set<T> = if (v in this) this - v else this + v
