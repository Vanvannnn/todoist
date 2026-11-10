package com.brr.app.ui.screens.calendars

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brr.app.AppContainer
import com.brr.app.calendar.CalendarRepository
import com.brr.app.calendar.PauseProfile
import com.brr.app.data.db.CalendarSourceEntity
import com.brr.app.domain.ReminderPlanner
import com.brr.app.ui.components.BrrCard
import com.brr.app.ui.components.ChoiceChip
import com.brr.app.ui.components.SettingRow
import com.brr.app.ui.screens.settings.Group
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CalendarsScreen(container: AppContainer, onBack: () -> Unit) {
    val repo = container.calendars
    val sources by repo.observeSources().collectAsStateWithLifecycle(initialValue = emptyList())
    val upcoming by repo.observeUpcoming().collectAsStateWithLifecycle(initialValue = emptyList())
    val s by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val st = s ?: return
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var message by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var addUrl by remember { mutableStateOf(false) }
    var devicePick by remember { mutableStateOf<List<CalendarRepository.DeviceCalendar>?>(null) }
    var pause by remember { mutableStateOf<String?>(null) }

    // Après toute modification : recalcul des pauses et reprogrammation de toutes les alarmes.
    fun afterChange(r: Result<Int>, ok: (Int) -> String) {
        message = r.fold(ok) { CalendarRepository.describe(it) }
        scope.launch { container.reminderCoordinator.rescheduleAll() }
    }
    LaunchedEffect(sources, st.pauseEnabled, st.pausedProfiles) {
        val now = System.currentTimeMillis()
        pause = ReminderPlanner.activePause(now, st.reminderSettings(repo.pauseWindows()))?.let { "Actif : ${it.title}, jusqu'à ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it.end))}" }
    }
    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val text = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty() }.getOrDefault("")
            afterChange(repo.addFile(uri.lastPathSegment?.substringAfterLast('/')?.removeSuffix(".ics") ?: "Fichier .ics", text, PauseProfile.COURS)) { "$it occurrences importées sur 6 mois." }
            busy = false
        }
    }
    val askCalendar = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) scope.launch { devicePick = repo.deviceCalendars() } else message = "Autorisation « Agenda » refusée : les agendas du téléphone restent invisibles pour Bullylist."
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }
        Text("Calendriers", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Bullylist lit tes cours et rendez-vous pour ne jamais te planifier une tâche pendant un engagement, et se tait pendant tes cours. Lecture seule.",
            style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted, modifier = Modifier.padding(horizontal = 20.dp))
        message?.let { Text(it, color = BrrPalette.Amber, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(20.dp, 10.dp)) }

        Group("Mode pause")
        SettingRow("Pause automatique", pause ?: "Pendant un engagement et le trajet qui suit, les rappels attendent. Aucun n'est perdu.") {
            Switch(st.pauseEnabled, { v -> scope.launch { container.settings.setPauseEnabled(v); container.reminderCoordinator.rescheduleAll() } })
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("Profils qui mettent les rappels en pause", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PauseProfile.entries.forEach { p ->
                    ChoiceChip(p.label, p.name in st.pausedProfiles, {
                        scope.launch { container.settings.setProfilePaused(p.name, p.name !in st.pausedProfiles); container.reminderCoordinator.rescheduleAll() }
                    })
                }
            }
            Spacer(Modifier.height(6.dp))
            Text("Sommeil : utilise la plage horaire autorisée (Réglages → Personnalisation des alarmes). Le niveau 4 respecte aussi les pauses, sauf pour une tâche où tu l'autorises explicitement.",
                style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
        }

        Group("Sources")
        SettingRow("Synchronisation", if (st.calendarSyncEnabled) "Actualisation automatique toutes les 6 heures." else "Désactivée : les données déjà importées restent utilisées.") {
            Switch(st.calendarSyncEnabled, { v -> scope.launch { container.settings.setCalendarSync(v) } })
        }
        sources.forEach { src -> SourceCard(src, onProfile = { p -> scope.launch { repo.update(src.copy(profile = p.name, travelBefore = null, travelAfter = null)); container.reminderCoordinator.rescheduleAll() } },
            onTravel = { m -> scope.launch { repo.update(src.copy(travelAfter = m)); container.reminderCoordinator.rescheduleAll() } },
            onRemove = { scope.launch { repo.remove(src.id); container.reminderCoordinator.rescheduleAll() } }) }
        if (sources.isEmpty()) Text("Aucun calendrier pour l'instant.", color = Brr.colors.muted, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
        FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { addUrl = true }, enabled = !busy) { Text("Lien iCal (CELCAT, iCloud…)") }
            OutlinedButton(onClick = { pickFile.launch(arrayOf("text/calendar", "text/plain", "application/octet-stream")) }, enabled = !busy) { Text("Fichier .ics") }
            OutlinedButton(onClick = { if (repo.hasCalendarPermission()) scope.launch { devicePick = repo.deviceCalendars() } else askCalendar.launch(Manifest.permission.READ_CALENDAR) }, enabled = !busy) { Text("Agendas du téléphone") }
            if (sources.isNotEmpty()) TextButton(onClick = { scope.launch { busy = true; afterChange(repo.sync()) { "$it événements à jour." }; busy = false } }, enabled = !busy) { Text("Synchroniser maintenant") }
        }
        Text("CELCAT : dans ton emploi du temps en ligne, cherche « Abonnement iCal », « Exporter » ou « ICS », puis copie le lien. " +
            "Apple : dans Calendrier iCloud, partage le calendrier en « Calendrier public » et copie le lien webcal://. " +
            "Google : ajoute ton compte au téléphone puis choisis « Agendas du téléphone » (aucun mot de passe demandé à Bullylist).",
            style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted, modifier = Modifier.padding(horizontal = 20.dp))

        Group("Planning automatique")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
            Text("Travail maximum par jour", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(180, 240, 300, 360, 420).forEach { m -> ChoiceChip("${m / 60} h", st.maxFocusMinutes == m, { scope.launch { container.settings.setMaxFocus(m) } }) }
            }
        }

        Group("Prochains engagements")
        upcoming.take(12).forEach { e ->
            val f = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            SettingRow(e.title, (if (e.allDay) "Journée entière (ne bloque pas le planning) · " else "") + f.format(Date(e.start)) + (e.location?.let { " · $it" } ?: ""))
        }
        Spacer(Modifier.height(100.dp))
    }

    if (addUrl) UrlDialog(onDismiss = { addUrl = false }) { name, url, profile ->
        addUrl = false
        scope.launch { busy = true; afterChange(repo.addUrl(name, url, profile)) { "Calendrier ajouté : $it événements sur 60 jours." }; busy = false }
    }
    devicePick?.let { list ->
        AlertDialog(onDismissRequest = { devicePick = null }, title = { Text("Agendas du téléphone") },
            text = {
                if (list.isEmpty()) Text("Aucun agenda trouvé. Ajoute ton compte Google dans les réglages du téléphone.")
                else Column { list.forEach { cal ->
                    val already = sources.any { it.deviceCalendarId == cal.id }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(already, { if (!already) scope.launch { afterChange(repo.addDevice(cal, PauseProfile.RDV)) { "« ${cal.name} » : $it événements." } } })
                        Column { Text(cal.name); Text(cal.account, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted) }
                    }
                } }
            },
            confirmButton = { TextButton(onClick = { devicePick = null }) { Text("Terminé") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SourceCard(src: CalendarSourceEntity, onProfile: (PauseProfile) -> Unit, onTravel: (Int) -> Unit, onRemove: () -> Unit) {
    val p = PauseProfile.of(src.profile)
    BrrCard(Modifier.padding(horizontal = 20.dp, vertical = 5.dp).fillMaxWidth()) {
        Text(src.name, style = MaterialTheme.typography.titleMedium)
        val kind = when (src.kind) { CalendarRepository.KIND_URL -> "Lien iCal"; CalendarRepository.KIND_FILE -> "Fichier (non actualisé)"; else -> "Agenda du téléphone" }
        val sync = src.lastSyncAt?.let { " · synchronisé " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it)) } ?: ""
        Text("$kind · ${src.eventCount} événements$sync", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
        src.lastError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = BrrPalette.Red) }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            PauseProfile.entries.forEach { ChoiceChip(it.label, it == p, { onProfile(it) }) }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trajet après : ${src.travelAfter ?: p.travelAfter} min", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = { onTravel(maxOf(0, (src.travelAfter ?: p.travelAfter) - 5)) }) { Text("−5") }
            TextButton(onClick = { onTravel(minOf(120, (src.travelAfter ?: p.travelAfter) + 5)) }) { Text("+5") }
            TextButton(onClick = onRemove) { Text("Retirer", color = BrrPalette.Red) }
        }
    }
}

@Composable
private fun UrlDialog(onDismiss: () -> Unit, onAdd: (String, String, PauseProfile) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    val valid = CalendarRepository.normalizeUrl(url) != null
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Ajouter un lien iCal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Nom (ex. Emploi du temps L2)") }, singleLine = true)
                OutlinedTextField(url, { url = it.trim() }, label = { Text("Lien https:// ou webcal://") }, singleLine = true,
                    isError = url.isNotEmpty() && !valid, supportingText = { if (url.isNotEmpty() && !valid) Text("Le lien doit commencer par https:// ou webcal://") })
                Text("Ce lien donne accès à ton emploi du temps : il reste uniquement sur ton téléphone.", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            }
        },
        confirmButton = { Button(onClick = { onAdd(name, url, PauseProfile.COURS) }, enabled = valid) { Text("Ajouter") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } })
}
