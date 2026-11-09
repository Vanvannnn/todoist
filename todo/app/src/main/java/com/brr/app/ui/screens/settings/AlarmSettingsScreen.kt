package com.brr.app.ui.screens.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brr.app.AppContainer
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.reminder.AlarmSounds
import com.brr.app.ui.components.BrrCard
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.components.ChoiceChip
import com.brr.app.ui.components.Fmt
import com.brr.app.ui.components.SettingRow
import com.brr.app.ui.components.TimePickDialog
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.screens.edit.Stepper
import com.brr.app.ui.theme.Brr

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlarmSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val vm = brrViewModel { SettingsViewModel(container) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val st = s ?: return
    var pickTime by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { vm.stopSound() } }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).statusBarsPadding()) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }
        Text("Alarmes", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Insistant, oui. Hors-la-loi, non : le mode silencieux et Ne pas déranger sont toujours respectés.",
            style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted, modifier = Modifier.padding(horizontal = 20.dp))

        Group("Escalade")
        Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Intervalles entre relances", style = MaterialTheme.typography.titleMedium)
            Text("Relance 1, 2, 3… La dernière valeur sert pour les suivantes.", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            Spacer(Modifier.height(10.dp))
            st.intervals.forEachIndexed { i, m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                    Text("Relance ${i + 1}", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Text("+${Fmt.minutes(m)}", style = MaterialTheme.typography.labelLarge, color = Brr.colors.muted, modifier = Modifier.padding(end = 8.dp))
                    Stepper(m, { v -> vm.setIntervals(st.intervals.toMutableList().also { it[i] = v }) }, 1..240)
                    IconButton(onClick = { if (st.intervals.size > 1) vm.setIntervals(st.intervals.filterIndexed { j, _ -> j != i }) }, enabled = st.intervals.size > 1) {
                        Icon(Icons.Default.Close, "Retirer", tint = Brr.colors.muted)
                    }
                }
            }
            if (st.intervals.size < 10) OutlinedButton(onClick = { vm.setIntervals(st.intervals + (st.intervals.lastOrNull() ?: 5)) }) {
                Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Ajouter une étape")
            }
            Spacer(Modifier.height(10.dp))
            Text(scenario(st.intervals, st.maxRelances), style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Nombre maximum de relances", style = MaterialTheme.typography.titleMedium)
                    Text("Évite les boucles infinies. 0 = un seul rappel.", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
                }
                Stepper(st.maxRelances, vm::setMaxRelances, 0..30)
            }
        }

        Group("Horaires")
        SettingRow("Plage horaire autorisée", if (st.allowedHoursEnabled) "Rappels entre ${Fmt.minuteOfDay(st.allowedStartMinute)} et ${Fmt.minuteOfDay(st.allowedEndMinute)}, décalés sinon."
        else "Rappels à toute heure.") {
            Switch(st.allowedHoursEnabled, { vm.setAllowedHours(it, st.allowedStartMinute, st.allowedEndMinute) })
        }
        if (st.allowedHoursEnabled) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceChip("Début ${Fmt.minuteOfDay(st.allowedStartMinute)}", true, { pickTime = "start" })
                ChoiceChip("Fin ${Fmt.minuteOfDay(st.allowedEndMinute)}", true, { pickTime = "end" })
            }
        }
        SettingRow("Heure des tâches « toute la journée »", "Elles sonnent à ${Fmt.minuteOfDay(st.allDayReminderMinute)}.", onClick = { pickTime = "allday" })

        Group("Sons")
        ReminderLevel.entries.forEach { l -> SoundRow(l, st.soundFor(l), { vm.setSound(l, it) }, { vm.playSound(it) }) }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Volume de l'écran immersif : ${(st.volume * 100).toInt()} %", style = MaterialTheme.typography.titleMedium)
            Text("Proportion du volume « notifications » du téléphone. Les notifications suivent le volume système.",
                style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            var vol by remember(st.volume) { mutableFloatStateOf(st.volume) }
            Slider(vol, { vol = it }, onValueChangeFinished = { vm.setVolume(vol) }, valueRange = 0.1f..1f)
        }

        Group("Vibrations")
        SettingRow("Vibrations", "Désactivées automatiquement en mode silencieux.") {
            Switch(st.vibrationEnabled, { vm.setVibration(it, st.vibrationStrength) })
        }
        if (st.vibrationEnabled) Column(Modifier.padding(horizontal = 20.dp)) {
            Text("Durée des vibrations : ×${"%.1f".format(st.vibrationStrength)}", style = MaterialTheme.typography.titleMedium)
            var v by remember(st.vibrationStrength) { mutableFloatStateOf(st.vibrationStrength) }
            Slider(v, { v = it }, onValueChangeFinished = { vm.setVibration(true, v) }, valueRange = 0.5f..2f, steps = 5)
        }

        Group("Aperçu des notifications")
        Text("Envoie un faux rappel pour voir (et entendre) chaque niveau.", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(10.dp))
        FlowRow(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ReminderLevel.entries.forEach { l -> ChoiceChip("Tester ${l.ordinal + 1} · ${l.label}", false, { vm.preview(l) }, Brr.colors.level(l), BrrIcons.Megaphone) }
        }
        Spacer(Modifier.height(10.dp))
        BrrCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            Text("Bon à savoir", style = MaterialTheme.typography.titleSmall)
            Text(
                "Android peut afficher l'écran immersif en simple notification si l'écran est déjà allumé ou si l'autorisation « plein écran » est refusée. " +
                    "Les sons d'un canal peuvent aussi être modifiés dans les réglages Android de l'appli.",
                style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted,
            )
        }
        Spacer(Modifier.height(80.dp))
    }

    pickTime?.let { which ->
        val init = when (which) { "start" -> st.allowedStartMinute; "end" -> st.allowedEndMinute; else -> st.allDayReminderMinute }
        TimePickDialog(init, { m ->
            when (which) {
                "start" -> vm.setAllowedHours(true, m, st.allowedEndMinute)
                "end" -> vm.setAllowedHours(true, st.allowedStartMinute, m)
                else -> vm.setAllDayMinute(m)
            }
            pickTime = null
        }, { pickTime = null })
    }
}

private fun scenario(intervals: List<Int>, max: Int): String {
    var t = 0
    val parts = mutableListOf("0 min : échéance")
    for (i in 1..minOf(max, 8)) {
        t += intervals[(i - 1).coerceAtMost(intervals.lastIndex)]
        parts += "+$t min : relance $i" + if (i == max) " (dernier avertissement)" else ""
    }
    if (max > 8) parts += "…"
    return "Scénario : " + parts.joinToString(" → ")
}

@Composable
private fun SoundRow(level: ReminderLevel, current: String, onChange: (String) -> Unit, onPlay: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(RoundedCornerShape(5.dp)).background(Brr.colors.level(level)))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text("Niveau ${level.ordinal + 1} · ${level.label}", style = MaterialTheme.typography.titleMedium)
            Text(AlarmSounds.byId(current).label, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
        }
        IconButton(onClick = { onPlay(current) }) { Icon(Icons.Default.PlayArrow, "Écouter") }
        DropdownMenu(open, { open = false }) {
            AlarmSounds.all.forEach { snd ->
                DropdownMenuItem(
                    text = { Column { Text(snd.label); Text(snd.description, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted) } },
                    onClick = { onChange(snd.id); onPlay(snd.id); open = false },
                    leadingIcon = { if (snd.id == current) Icon(BrrIcons.Volume, null, tint = MaterialTheme.colorScheme.primary) },
                )
            }
        }
    }
}
