package com.brr.app.ui.screens.settings

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brr.app.AppContainer
import com.brr.app.BuildConfig
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.ThemeMode
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.components.ChoiceChip
import com.brr.app.ui.components.SettingRow
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.theme.Brr

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(container: AppContainer, onAlarms: () -> Unit, onPunchlines: () -> Unit, onPermissions: () -> Unit, onHistory: () -> Unit) {
    val vm = brrViewModel { SettingsViewModel(container) }
    val s by vm.settings.collectAsStateWithLifecycle()
    val st = s ?: return

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).statusBarsPadding()) {
        Spacer(Modifier.height(20.dp))
        Text("Réglages", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Règle le niveau de nuisance qui te convient.", style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted, modifier = Modifier.padding(horizontal = 20.dp))

        Group("Rappels")
        SettingRow("Mode B.R.R.", if (st.brrModeEnabled) "Activé : le niveau 4 peut se déclencher." else "Désactivé : les rappels plafonnent au niveau Harcèlement.", BrrIcons.Bolt) {
            Switch(st.brrModeEnabled, vm::setBrr)
        }
        SettingRow("Escalade progressive", "Chaque relance monte d'un niveau, jusqu'au niveau choisi pour la tâche.", BrrIcons.Megaphone) {
            Switch(st.progressiveEscalation, vm::setProgressive)
        }
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Niveau par défaut des nouvelles tâches", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ReminderLevel.entries.forEach { l -> ChoiceChip(l.label, st.defaultLevel == l, { vm.setDefaultLevel(l) }, Brr.colors.level(l)) }
            }
        }
        SettingRow("Personnalisation des alarmes", "Relances, horaires, sons, volume, vibrations, aperçus", BrrIcons.Alarm, onClick = onAlarms) { Chevron() }
        SettingRow("Bibliothèque de punchlines", "Catégories, messages perso, contextualisation", BrrIcons.Notes, onClick = onPunchlines) { Chevron() }
        SettingRow("Fiabilité et autorisations", "Notifications, alarmes exactes, plein écran, batterie", BrrIcons.Shield, onClick = onPermissions) { Chevron() }

        Group("Apparence")
        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text("Thème", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { m -> ChoiceChip(m.label, st.themeMode == m, { vm.setTheme(m) }) }
            }
        }

        Group("Données")
        SettingRow("Historique", "Tâches terminées et journal d'activité", BrrIcons.History, onClick = onHistory) { Chevron() }
        SettingRow("Stockage", "Tout reste sur ton téléphone. Aucun compte, aucun serveur, aucune pub.", Icons.Default.Info)

        Group("À propos")
        SettingRow("B.R.R. — Be Right Responsible", "Version ${BuildConfig.VERSION_NAME}. Sons et punchlines originaux. Polices Space Grotesk et Inter (SIL OFL).")
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brr.colors.muted)

@Composable
fun Group(title: String) {
    Spacer(Modifier.height(18.dp))
    HorizontalDivider(color = Brr.colors.hairline)
    Text(title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 4.dp))
}
