package com.brr.app.ui.screens.edit

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brr.app.AppContainer
import com.brr.app.domain.RecurrenceCalculator
import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.RepeatType
import com.brr.app.domain.model.WeekDays
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.components.CheckCircle
import com.brr.app.ui.components.ChoiceChip
import com.brr.app.ui.components.Fmt
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.theme.Brr
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun TaskEditScreen(
    container: AppContainer, taskId: Long, presetProjectId: Long?, presetText: String?,
    onClose: () -> Unit, onSaved: (Long, Boolean) -> Unit,
) {
    val vm = brrViewModel(key = "edit_$taskId") { TaskEditViewModel(container, taskId, presetProjectId, presetText) }
    val s by vm.state.collectAsStateWithLifecycle()
    val projects by vm.projects.collectAsStateWithLifecycle()
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    LaunchedEffect(s.savedId) { s.savedId?.let { onSaved(it, s.isNew) } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Fermer") }
            Text(if (s.isNew) "Nouvelle tâche" else "Modifier", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Button(onClick = vm::save, enabled = !s.saving && !s.loading, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(end = 8.dp)) {
                if (s.saving) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White) else Text("Enregistrer")
            }
        }
        if (s.loading) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Column
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding()) {
            s.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f)).padding(12.dp))
            }
            BareField(s.title, { v -> vm.update { it.copy(title = v) } }, "Que faut-il faire ?", MaterialTheme.typography.headlineSmall)
            BareField(s.description, { v -> vm.update { it.copy(description = v) } }, "Description, notes, détails…", MaterialTheme.typography.bodyLarge, singleLine = false)

            Section("Échéance")
            val today = LocalDate.now()
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Aucune", s.date == null, { vm.update { it.copy(date = null, time = null, repeatType = RepeatType.NONE) } })
                ChoiceChip("Aujourd'hui", s.date == today, { vm.update { it.copy(date = today) } })
                ChoiceChip("Demain", s.date == today.plusDays(1), { vm.update { it.copy(date = today.plusDays(1)) } })
                val monday = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
                ChoiceChip("Lundi prochain", s.date == monday, { vm.update { it.copy(date = monday) } })
                val custom = s.date != null && s.date !in listOf(today, today.plusDays(1), monday)
                ChoiceChip(if (custom) DateTimeFormatter.ofPattern("EEE d MMM", Locale.FRENCH).format(s.date) else "Choisir…", custom, { showDate = true }, icon = BrrIcons.Today)
            }
            if (s.date != null) {
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip(s.time?.let { "À ${Fmt.minuteOfDay(it.hour * 60 + it.minute)}" } ?: "Ajouter une heure", s.time != null, { showTime = true }, icon = BrrIcons.Alarm)
                    if (s.time != null) ChoiceChip("Toute la journée", false, { vm.update { it.copy(time = null) } })
                }
            }

            Section("Priorité")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.reversed().forEach { p ->
                    ChoiceChip(p.label, s.priority == p, { vm.update { it.copy(priority = p) } }, Brr.colors.priority(p), BrrIcons.Flag)
                }
            }

            Section("Projet")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Aucun", s.projectId == null, { vm.update { it.copy(projectId = null) } })
                projects.forEach { p -> ChoiceChip(p.name, s.projectId == p.id, { vm.update { it.copy(projectId = p.id) } }, Color(p.color), BrrIcons.Folder) }
            }

            Section("Rappel")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Rappels activés", style = MaterialTheme.typography.titleMedium)
                    Text(if (s.date == null) "Ajoute une échéance pour être rappelé." else "Escalade jusqu'à ce que tu agisses.",
                        style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
                }
                Switch(s.remindersEnabled, { v -> vm.update { it.copy(remindersEnabled = v) } })
            }
            if (s.remindersEnabled) {
                Spacer(Modifier.height(12.dp))
                ReminderLevel.entries.forEach { l -> LevelCard(l, s.level == l) { vm.update { it.copy(level = l) } } }
                if (s.date != null) {
                    SubLabel("Rappels supplémentaires avant l'échéance")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 15, 30, 60, 180, 1440).forEach { m ->
                            ChoiceChip("${Fmt.minutes(m)} avant", m in s.offsets, {
                                vm.update { it.copy(offsets = if (m in it.offsets) it.offsets - m else it.offsets + m) }
                            })
                        }
                    }
                }
                SubLabel("Intervalle des relances")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceChip("Réglage global", s.nagInterval == null, { vm.update { it.copy(nagInterval = null) } })
                    listOf(2, 5, 10, 15, 30, 60).forEach { m ->
                        ChoiceChip("Toutes les ${Fmt.minutes(m)}", s.nagInterval == m, { vm.update { it.copy(nagInterval = m) } })
                    }
                }
                SubLabel("Message personnalisé")
                BareField(s.customMessage, { v -> vm.update { it.copy(customMessage = v) } },
                    "Ex. : « Pense à ton dos, plie les genoux. »", MaterialTheme.typography.bodyLarge, boxed = true)
            }

            Section("Répétition")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RepeatType.entries.forEach { r ->
                    ChoiceChip(r.label, s.repeatType == r, {
                        vm.update { it.copy(repeatType = r, date = if (r != RepeatType.NONE && it.date == null) LocalDate.now() else it.date) }
                    }, icon = if (r == RepeatType.NONE) null else BrrIcons.Repeat)
                }
            }
            if (s.repeatType != RepeatType.NONE) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Intervalle", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Stepper(s.repeatInterval, { v -> vm.update { it.copy(repeatInterval = v) } }, 1..99)
                }
                if (s.repeatType == RepeatType.WEEKLY) {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WeekDays.labels.forEachIndexed { i, l ->
                            val on = WeekDays.has(s.repeatDays, i + 1)
                            Box(
                                Modifier.size(38.dp).clip(CircleShape)
                                    .background(if (on) MaterialTheme.colorScheme.primary else Brr.colors.cardHigh)
                                    .clickable { vm.update { it.copy(repeatDays = WeekDays.toggle(it.repeatDays, i + 1)) } },
                                contentAlignment = Alignment.Center,
                            ) { Text(l, color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(RecurrenceCalculator.describe(s.repeatType, s.repeatInterval, s.repeatDays), style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            }

            Section("Sous-tâches")
            s.subtasks.forEach { st ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CheckCircle(st.done, Brr.colors.muted, { vm.update { e -> e.copy(subtasks = e.subtasks.map { if (it.key == st.key) it.copy(done = !it.done) else it }) } }, 18.dp)
                    BareField(st.title, { v -> vm.update { e -> e.copy(subtasks = e.subtasks.map { if (it.key == st.key) it.copy(title = v) else it }) } },
                        "Sous-tâche", MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = { vm.update { e -> e.copy(subtasks = e.subtasks.filter { it.key != st.key }) } }) {
                        Icon(Icons.Default.Close, "Supprimer", tint = Brr.colors.muted)
                    }
                }
            }
            var newSub by remember { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 11.dp))
                TextField(
                    value = newSub, onValueChange = { newSub = it }, placeholder = { Text("Ajouter une sous-tâche") },
                    singleLine = true, modifier = Modifier.weight(1f), colors = bareColors(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { vm.addSubtask(newSub); newSub = "" }),
                )
                if (newSub.isNotBlank()) TextButton(onClick = { vm.addSubtask(newSub); newSub = "" }) { Text("Ajouter") }
            }
            Spacer(Modifier.height(48.dp))
        }
    }

    if (showDate) {
        val st = rememberDatePickerState(initialSelectedDateMillis = (s.date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDate = false },
            confirmButton = {
                TextButton(onClick = {
                    st.selectedDateMillis?.let { ms -> vm.update { it.copy(date = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()) } }
                    showDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showDate = false }) { Text("Annuler") } },
        ) { DatePicker(st) }
    }
    if (showTime) {
        val init = s.time ?: LocalTime.now().plusHours(1).withMinute(0)
        val tp = rememberTimePickerState(init.hour, init.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { showTime = false },
            confirmButton = { TextButton(onClick = { vm.update { it.copy(time = LocalTime.of(tp.hour, tp.minute)) }; showTime = false }) { Text("OK") } },
            dismissButton = { TextButton(onClick = { showTime = false }) { Text("Annuler") } },
            text = { TimePicker(tp) },
        )
    }
}

@Composable
private fun LevelCard(level: ReminderLevel, selected: Boolean, onClick: () -> Unit) {
    val c = Brr.colors.level(level)
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(shape)
            .background(if (selected) c.copy(alpha = 0.14f) else Brr.colors.card)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c else Brr.colors.hairline, shape)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(c.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
            Text("${level.ordinal + 1}", color = c, style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(level.label, style = MaterialTheme.typography.titleMedium, color = if (selected) c else MaterialTheme.colorScheme.onBackground)
            Text(level.tagline, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
        }
        if (level == ReminderLevel.BRR) Icon(BrrIcons.Bolt, null, tint = c)
    }
}

@Composable
fun Stepper(value: Int, onChange: (Int) -> Unit, range: IntRange) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Brr.colors.cardHigh), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { onChange((value - 1).coerceIn(range)) }) { Text("−", style = MaterialTheme.typography.titleLarge) }
        Text("$value", style = MaterialTheme.typography.titleMedium, modifier = Modifier.width(32.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        TextButton(onClick = { onChange((value + 1).coerceIn(range)) }) { Text("+", style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun Section(title: String) {
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brr.colors.muted, modifier = Modifier.padding(top = 26.dp, bottom = 10.dp))
}

@Composable
private fun SubLabel(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(top = 18.dp, bottom = 8.dp))
}

@Composable
private fun bareColors() = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
)

@Composable
private fun BareField(
    value: String, onChange: (String) -> Unit, placeholder: String, style: TextStyle,
    singleLine: Boolean = false, boxed: Boolean = false, modifier: Modifier = Modifier.fillMaxWidth(),
) {
    TextField(
        value = value, onValueChange = onChange, placeholder = { Text(placeholder, style = style, color = Brr.colors.faint) },
        textStyle = style, singleLine = singleLine, modifier = modifier,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        shape = RoundedCornerShape(12.dp),
        colors = if (boxed) TextFieldDefaults.colors(
            focusedContainerColor = Brr.colors.cardHigh, unfocusedContainerColor = Brr.colors.cardHigh,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ) else bareColors(),
    )
}
