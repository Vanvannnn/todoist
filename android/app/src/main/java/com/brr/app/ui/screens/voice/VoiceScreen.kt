package com.brr.app.ui.screens.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brr.app.AppContainer
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.voice.PlanDates
import com.brr.app.domain.voice.Recurrence
import com.brr.app.domain.voice.SchedulePlanner
import com.brr.app.domain.voice.VoiceTask
import com.brr.app.ui.components.BrrCard
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.components.LevelBadge
import com.brr.app.ui.components.Pill
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette
import com.brr.app.ui.theme.Mono
import kotlinx.coroutines.launch

private val SAMPLE = "Cette semaine, j'aimerais réviser les mathématiques, avancer mon dossier de marketing, faire les courses, ranger mon appartement et préparer mon exposé. " +
    "Il faudrait que le dossier de marketing soit terminé avant jeudi soir. Les mathématiques, j'aimerais bien en faire un peu chaque jour. " +
    "Les courses, je peux les faire demain après-midi. Et ce soir, il faudrait absolument que je termine mon exposé parce que je dois le rendre demain matin."

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceScreen(container: AppContainer, onBack: () -> Unit, onSaved: (Int) -> Unit) {
    val vm = brrViewModel { VoiceViewModel(container) }
    val st by vm.state.collectAsStateWithLifecycle()
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var level by remember { mutableFloatStateOf(0f) }
    var listening by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val recognizer = remember {
        VoiceRecognizer(ctx, onText = { f, p -> vm.setTranscript(f, p) }, onLevel = { level = it }, onState = { s ->
            listening = s == VoiceRecognizer.State.LISTENING
            error = when (s) {
                VoiceRecognizer.State.ERROR_NO_SERVICE -> "Aucun service de reconnaissance vocale sur ce téléphone. Écris ton texte : l'analyse est identique."
                VoiceRecognizer.State.ERROR_PERMISSION -> "Micro refusé. Autorise-le dans les réglages du téléphone, ou écris ton texte."
                VoiceRecognizer.State.ERROR_NETWORK -> "La reconnaissance a besoin du réseau (pack de langue hors ligne absent). Écris ton texte ou installe le pack français."
                else -> null
            }
            if (s == VoiceRecognizer.State.IDLE && !listening && vm.state.value.transcript.isNotBlank() && vm.state.value.tasks.isEmpty()) vm.analyse()
        })
    }
    DisposableEffect(Unit) { onDispose { recognizer.release() } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) recognizer.start(st.transcript) else error = "Micro refusé : tu peux écrire ton texte, l'analyse est identique."
    }
    fun toggleMic() {
        if (listening) { recognizer.pause(); vm.analyse(); return }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) recognizer.start(st.transcript)
        else askMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Raconte-moi ta semaine.", style = MaterialTheme.typography.displaySmall)
            Text("Parle comme à un ami. Je repère les tâches, les échéances et les durées, puis je les place entre tes cours.",
                style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted)
            Spacer(Modifier.height(16.dp))

            if (settings?.voiceConsent == false) {
                BrrCard(Modifier.fillMaxWidth()) {
                    Text("Avant de parler", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("La dictée utilise la reconnaissance vocale de ton téléphone. Si le pack de langue français est installé, elle fonctionne hors ligne ; " +
                        "sinon Android peut envoyer le son au service de Google. Bullylist n'enregistre ni ne conserve aucun audio : seul le texte est analysé, sur le téléphone.",
                        style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { scope.launch { container.settings.setVoiceConsent() } }) { Text("J'ai compris") }
                }
                Spacer(Modifier.height(16.dp))
            }

            // Micro : l'anneau suit le niveau sonore.
            val ring by animateFloatAsState(if (listening) 1f + level * 0.35f else 1f, label = "ring")
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(132.dp).scale(ring).border(2.dp, (if (listening) BrrPalette.Red else MaterialTheme.colorScheme.primary).copy(alpha = 0.25f + level * 0.6f), CircleShape))
                Box(
                    Modifier.size(108.dp).clip(CircleShape).background(if (listening) BrrPalette.Red else MaterialTheme.colorScheme.primary)
                        .clickable(enabled = settings?.voiceConsent == true) { toggleMic() }
                        .semantics { contentDescription = if (listening) "Mettre la dictée en pause" else "Démarrer la dictée" },
                    contentAlignment = Alignment.Center,
                ) { Icon(BrrIcons.Mic, null, tint = if (listening) Color.White else MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(40.dp)) }
            }
            Text(if (listening) "J'écoute… touche pour mettre en pause." else "Touche le micro pour parler, ou écris ci-dessous.",
                style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted, modifier = Modifier.align(Alignment.CenterHorizontally))
            error?.let { Spacer(Modifier.height(8.dp)); Text(it, color = BrrPalette.Amber, style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = st.transcript + if (st.partial.isNotEmpty()) " " + st.partial else "",
                onValueChange = { vm.setTranscript(it) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text("Transcription (modifiable)") },
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { recognizer.pause(); vm.analyse() }, enabled = st.transcript.isNotBlank() || st.partial.isNotBlank()) { Text("Analyser") }
                OutlinedButton(onClick = { recognizer.pause(); vm.restart() }) { Text("Recommencer") }
                TextButton(onClick = { vm.setTranscript(SAMPLE); vm.analyse() }) { Text("Exemple") }
            }

            if (st.questions.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                st.questions.forEach { Text(it, color = BrrPalette.Amber, style = MaterialTheme.typography.bodyMedium) }
            }
            if (st.tasks.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                val single = !st.isPlanRequest && st.tasks.size == 1 && st.questions.isEmpty()
                Text(if (single) "Je l'ajoute comme ceci :" else "Voici comment je te propose d'organiser ta semaine.", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(10.dp))
                st.tasks.forEach { TaskCard(it, st, vm) }
                st.plan?.let { PlanView(it, st) }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.accept(onSaved) }, enabled = !st.saving && st.plan != null) { Text(if (single) "Ajouter" else "Tout accepter") }
                    OutlinedButton(onClick = { vm.replan() }) { Text("Recommencer la planification") }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskCard(t: VoiceTask, st: VoiceUiState, vm: VoiceViewModel) {
    var dayMenu by remember { mutableStateOf(false) }
    var levelMenu by remember { mutableStateOf(false) }
    BrrCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { vm.remove(t.id) }) { Icon(Icons.Default.Close, "Supprimer ${t.title}", tint = Brr.colors.muted) }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            t.deadline?.let { Pill("Avant ${PlanDates.fmtDay(it.date, st.today).lowercase()} ${PlanDates.fmtTime(it.minute)}", BrrPalette.Orange) }
            t.window?.let { Pill(if (it.fixed) "À ${PlanDates.fmtTime(it.start)}, ${PlanDates.fmtDay(it.date, st.today).lowercase()}" else "Souhaité : ${PlanDates.fmtDay(it.date, st.today).lowercase()}", Brr.colors.muted) }
            if (t.recurrence?.type == Recurrence.Type.DAILY) Pill("Chaque jour", Brr.colors.muted)
            if (t.recurrence?.type == Recurrence.Type.COUNT) Pill("${t.recurrence?.n} séances", Brr.colors.muted)
            val est = t.estimate
            Pill(if (t.recurrence?.type == Recurrence.Type.DAILY) "≈ ${t.sessionMinutes} min / jour" else "≈ ${t.minutes} min" + (if (t.explicitMinutes == null && est != null) " (${est.min}–${est.max})" else ""), Brr.colors.muted)
        }
        if (t.notes.isNotEmpty()) { Spacer(Modifier.height(6.dp)); Text(t.notes.joinToString("\n"), style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = { vm.changeMinutes(t.id, -15) }) { Text("−15 min") }
            TextButton(onClick = { vm.changeMinutes(t.id, 15) }) { Text("+15 min") }
            Box {
                TextButton(onClick = { dayMenu = true }) { Text("Jour") }
                DropdownMenu(dayMenu, { dayMenu = false }) {
                    DropdownMenuItem({ Text("Automatique") }, { vm.moveTo(t.id, null); dayMenu = false })
                    (0..6).forEach { i -> val d = st.today.plusDays(i.toLong()); DropdownMenuItem({ Text(PlanDates.fmtDay(d, st.today)) }, { vm.moveTo(t.id, d); dayMenu = false }) }
                }
            }
            Box {
                TextButton(onClick = { levelMenu = true }) { LevelBadge(t.level, compact = true) }
                DropdownMenu(levelMenu, { levelMenu = false }) {
                    ReminderLevel.entries.forEach { l -> DropdownMenuItem({ Text(l.label) }, { vm.setLevel(t.id, l); levelMenu = false }) }
                }
            }
        }
    }
}

@Composable
private fun PlanView(r: SchedulePlanner.Result, st: VoiceUiState) {
    Spacer(Modifier.height(12.dp))
    if (!r.feasible) Text("Planning impossible tel quel : voici ce que je propose.", color = BrrPalette.Red, style = MaterialTheme.typography.titleMedium)
    r.unplaced.forEach { u ->
        BrrCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text("${u.title} — ${u.minutes} min non placées", style = MaterialTheme.typography.titleSmall)
            Text("Raison : ${u.reason}.", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            u.alternatives.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
    r.warnings.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = BrrPalette.Amber, modifier = Modifier.padding(vertical = 2.dp)) }
    r.days.filter { d -> d.blocks.any { it.kind == SchedulePlanner.Kind.TASK || it.kind == SchedulePlanner.Kind.EVENT } }.forEach { d ->
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(PlanDates.fmtDay(d.date, st.today), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("${d.focus} min de travail · ${d.free} min libres", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
        }
        Spacer(Modifier.height(6.dp))
        d.blocks.forEach { b ->
            val task = b.kind == SchedulePlanner.Kind.TASK
            val bg = when (b.kind) { SchedulePlanner.Kind.TASK -> Brr.colors.cardHigh; SchedulePlanner.Kind.EVENT -> Brr.colors.card; else -> Color.Transparent }
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp).clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 10.dp, vertical = if (task || b.kind == SchedulePlanner.Kind.EVENT) 8.dp else 3.dp),
                verticalAlignment = Alignment.CenterVertically) {
                if (task) Box(Modifier.width(3.dp).height(18.dp).background(Brr.colors.level(b.level ?: ReminderLevel.INSISTANT)))
                Spacer(Modifier.width(6.dp))
                Text("${PlanDates.fmtTime(b.start)}–${PlanDates.fmtTime(minOf(b.end, 1439))}", fontFamily = Mono, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted, modifier = Modifier.width(104.dp))
                Text(b.title + (b.location?.let { " · $it" } ?: ""), style = if (task) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                    fontWeight = if (task) FontWeight.SemiBold else FontWeight.Normal, color = if (task || b.kind == SchedulePlanner.Kind.EVENT) MaterialTheme.colorScheme.onBackground else Brr.colors.muted)
            }
        }
    }
}
