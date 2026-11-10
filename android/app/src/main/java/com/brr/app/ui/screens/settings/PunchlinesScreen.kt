package com.brr.app.ui.screens.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brr.app.AppContainer
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.punchline.PunchlineCatalog
import com.brr.app.punchline.PunchlineCategory
import com.brr.app.ui.components.BrrCard
import com.brr.app.ui.components.ChoiceChip
import com.brr.app.ui.components.SettingRow
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.theme.Brr

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PunchlinesScreen(container: AppContainer, onBack: () -> Unit) {
    val vm = brrViewModel { SettingsViewModel(container) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val custom by vm.customPunchlines.collectAsStateWithLifecycle()
    val sample by vm.sample.collectAsStateWithLifecycle()
    val st = s ?: return
    var sampleLevel by remember { mutableStateOf(ReminderLevel.INSISTANT) }
    var expanded by remember { mutableStateOf<PunchlineCategory?>(null) }
    var text by remember { mutableStateOf("") }
    var cat by remember { mutableStateOf(PunchlineCategory.SARCASTIQUE) }
    var intensity by remember { mutableIntStateOf(2) }
    val byCat = remember { PunchlineCatalog.all.groupBy { it.category } }

    LazyColumn(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentPadding = PaddingValues(bottom = 80.dp)) {
        item {
            Column(Modifier.statusBarsPadding()) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }
                Text("Punchlines", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 20.dp))
                Text("${PunchlineCatalog.all.size} intégrées + ${custom.size} perso. Tirées au hasard, sans répétition récente.",
                    style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted, modifier = Modifier.padding(horizontal = 20.dp))
                Spacer(Modifier.height(16.dp))
                BrrCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
                    Text("ESSAYER", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.height(8.dp))
                    Text(sample?.let { "« $it »" } ?: "Choisis un niveau et tire une punchline.", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ReminderLevel.entries.forEach { l -> ChoiceChip("${l.ordinal + 1}", sampleLevel == l, { sampleLevel = l }, Brr.colors.level(l)) }
                        Button(onClick = { vm.drawSample(sampleLevel) }) { Icon(Icons.Default.Refresh, null); Text("  Tirer") }
                    }
                }
                Group("Intensité de l'humour")
                FlowRow(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Douce", "Piquante", "Sévère", "Nucléaire").forEachIndexed { i, l ->
                        ChoiceChip(l, st.humorMax == i + 1, { vm.setHumorMax(i + 1) }, Brr.colors.level(ReminderLevel.fromIndex(i)))
                    }
                }
                Text("Les phrases plus piquantes que ce niveau ne sont jamais tirées, même en Bully Mode: Nuclear.",
                    style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted, modifier = Modifier.padding(horizontal = 20.dp))
                Group("Catégories")
                SettingRow("Messages contextuels", "Utilise le nom de la tâche (« La vaisselle ne va pas se laver… »).") {
                    Switch(st.contextualPunchlines, vm::setContextual)
                }
            }
        }
        items(PunchlineCategory.entries) { c ->
            val enabled = c !in st.disabledCategories
            Column {
                Row(Modifier.fillMaxWidth().clickable { expanded = if (expanded == c) null else c }.padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${c.label}  ·  ${byCat[c]?.size ?: 0}", style = MaterialTheme.typography.titleMedium,
                            color = if (enabled) MaterialTheme.colorScheme.onBackground else Brr.colors.muted)
                        Text(c.description, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
                    }
                    Icon(if (expanded == c) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = Brr.colors.muted)
                    Switch(enabled, { vm.setCategory(c, it) }, modifier = Modifier.padding(start = 8.dp))
                }
                AnimatedVisibility(expanded == c) {
                    Column(Modifier.padding(start = 28.dp, end = 20.dp, bottom = 8.dp)) {
                        byCat[c].orEmpty().forEach { p ->
                            Text("${"●".repeat(p.intensity)}  ${p.text}", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 3.dp))
                        }
                    }
                }
            }
        }
        item {
            Group("Mes punchlines")
            Column(Modifier.padding(horizontal = 20.dp)) {
                OutlinedTextField(text, { text = it }, label = { Text("Nouvelle punchline") }, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ex. : Même ton chat est déçu.") })
                Spacer(Modifier.height(10.dp))
                Text("Catégorie", style = MaterialTheme.typography.labelMedium, color = Brr.colors.muted)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    PunchlineCategory.entries.forEach { c -> ChoiceChip(c.label, cat == c, { cat = c }) }
                }
                Spacer(Modifier.height(10.dp))
                Text("Intensité", style = MaterialTheme.typography.labelMedium, color = Brr.colors.muted)
                Spacer(Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..4).forEach { i -> ChoiceChip("$i", intensity == i, { intensity = i }, Brr.colors.level(ReminderLevel.fromIndex(i - 1))) }
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { vm.addPunchline(text, cat, intensity); text = "" }, enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Ajouter") }
                Spacer(Modifier.height(10.dp))
                Text("Rappel : humour oui, cruauté non. Évite les attaques personnelles.", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            }
        }
        items(custom, key = { "c${it.id}" }) { p ->
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.text, style = MaterialTheme.typography.bodyLarge)
                    Text("${p.category.label} · intensité ${p.intensity}", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
                }
                IconButton(onClick = { vm.deletePunchline(p) }) { Icon(Icons.Default.Delete, "Supprimer", tint = Brr.colors.muted) }
            }
        }
    }
}
