package com.brr.app.ui.screens.settings

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brr.app.AppContainer
import com.brr.app.reminder.PermissionSnapshot
import com.brr.app.ui.components.BrrCard
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette

@Composable
fun PermissionsScreen(container: AppContainer, onBack: (() -> Unit)?) {
    val vm = brrViewModel { SettingsViewModel(container) }
    val report by vm.report.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).statusBarsPadding()) {
        if (onBack != null) Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }
        Text("Fiabilité", style = MaterialTheme.typography.displaySmall, modifier = Modifier.padding(horizontal = 20.dp))
        Text("Ce dont Bullylist a besoin pour te harceler à l'heure.", style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted,
            modifier = Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(16.dp))
        PermissionList(container)
        Spacer(Modifier.height(16.dp))
        BrrCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            Text("Vérification des rappels", style = MaterialTheme.typography.titleMedium)
            Text("Reprogramme toutes les alarmes et répare celles qu'Android aurait supprimées. Fait aussi automatiquement au démarrage et toutes les 6 h.",
                style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = vm::verify) { Text("Vérifier maintenant") }
            report?.let {
                Text("${it.scheduled} rappel(s) programmé(s), ${it.repaired} réparé(s), ${it.cancelled} nettoyé(s).",
                    style = MaterialTheme.typography.bodyMedium, color = Brr.colors.success, modifier = Modifier.padding(top = 8.dp))
            }
        }
        Spacer(Modifier.height(16.dp))
        BrrCard(Modifier.padding(horizontal = 20.dp).fillMaxWidth()) {
            Text("Soyons honnêtes", style = MaterialTheme.typography.titleMedium)
            Text(
                "Aucune application ne peut garantir une alarme à 100 % : Android et certains constructeurs (Xiaomi, Huawei, Oppo, Samsung…) " +
                    "peuvent retarder ou bloquer les applis en arrière-plan. Autorise le démarrage automatique et retire les restrictions de batterie " +
                    "pour Bullylist dans les réglages du téléphone. Le site dontkillmyapp.com détaille la marche à suivre par marque.",
                style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted,
            )
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { container.permissions.launch(container.permissions.appDetailsIntent(), null) }) { Text("Ouvrir les infos de l'appli") }
        }
        Spacer(Modifier.height(80.dp))
    }
}

/** Liste des autorisations avec état et bouton d'action (réutilisée dans l'accueil). */
@Composable
fun PermissionList(container: AppContainer) {
    val pc = container.permissions
    var snap by remember { mutableStateOf<PermissionSnapshot?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { snap = pc.snapshot() }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        snap = pc.snapshot()
        if (!granted) pc.launch(pc.notificationSettingsIntent())
    }
    val s = snap ?: return
    Column(Modifier.padding(horizontal = 20.dp)) {
        PermissionCard(
            "Notifications", "Indispensable : sans elles, aucun rappel ne peut s'afficher.", s.notifications,
        ) {
            if (pc.needsRuntimeNotificationPermission()) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            else pc.launch(pc.notificationSettingsIntent())
        }
        PermissionCard(
            "Alarmes exactes", "Pour sonner à la minute près. Sinon, Android peut décaler les rappels de plusieurs minutes.", s.exactAlarms,
        ) { pc.launch(pc.exactAlarmIntent()) }
        PermissionCard(
            "Écran plein écran", "Pour afficher l'écran immersif (niveaux 3 et 4) sur l'écran verrouillé. Sinon : notification prioritaire.", s.fullScreen,
        ) { pc.launch(pc.fullScreenIntent(), pc.notificationSettingsIntent()) }
        PermissionCard(
            "Batterie sans restriction", "Évite que l'optimisation de batterie ne retarde les rappels.", s.batteryUnrestricted,
        ) { pc.launch(pc.batteryIntent(), pc.batteryFallbackIntent()) }
        BrrCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Text("Mode du téléphone", style = MaterialTheme.typography.titleMedium)
            Text(s.policy.reason, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            Text("Bullylist ne contourne jamais le silencieux, le vibreur ou Ne pas déranger.", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
        }
    }
}

@Composable
private fun PermissionCard(title: String, desc: String, ok: Boolean, onFix: () -> Unit) {
    BrrCard(Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val c = if (ok) Brr.colors.success else BrrPalette.Amber
            Box(Modifier.size(30.dp).clip(CircleShape).background(c.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Icon(if (ok) Icons.Default.Check else Icons.Default.Warning, null, tint = c, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(if (ok) "Autorisé" else "Action requise", style = MaterialTheme.typography.labelMedium, color = c)
            }
            if (!ok) Button(onClick = onFix) { Text("Autoriser") }
        }
        Spacer(Modifier.height(6.dp))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
    }
}
