package com.brr.app.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brr.app.AppContainer
import com.brr.app.ui.components.BrrCard
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Réglages de l'assistant IA : activation, serveur Bullylist, jeton d'application, accord d'envoi des dictées. */
@Composable
fun AssistantSettingsScreen(container: AppContainer, onBack: () -> Unit) {
    val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val s = settings ?: return
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(s.aiServerUrl) }
    var token by remember { mutableStateOf(s.aiToken) }
    var test by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(s.aiServerUrl) { if (url.isEmpty()) url = s.aiServerUrl }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().imePadding().verticalScroll(rememberScrollState())) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") } }
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Assistant IA", style = MaterialTheme.typography.displaySmall)
            Text("Pour comprendre les dictées longues, les précisions et les changements d'avis, Bullylist peut faire analyser le texte par une intelligence artificielle. " +
                "Sans elle, l'analyse reste faite sur le téléphone (plus simple, mais moins fine).", style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted)

            BrrCard(Modifier.fillMaxWidth()) {
                Text("Ce qui est envoyé", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text("• Le texte de ta dictée, la date, l'heure et le fuseau du téléphone, et le planning en cours de discussion.\n" +
                    "• Jamais l'audio, jamais tes autres tâches, jamais ton agenda.\n" +
                    "• Le serveur Bullylist le transmet au fournisseur IA (Anthropic, modèle Claude, ou Google, modèle Gemini, selon le serveur) pour l'analyse et ne l'enregistre pas.\n" +
                    "• Sans ton accord, rien ne part : l'analyse reste sur le téléphone.",
                    style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("J'accepte l'envoi du texte de mes dictées", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(checked = s.aiConsent, onCheckedChange = { v -> scope.launch { container.settings.setAiConsent(v) } })
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Utiliser l'assistant IA", style = MaterialTheme.typography.titleMedium)
                    Text(if (s.aiReady) "Actif. En cas de panne réseau, l'analyse locale prend le relais." else "Inactif : analyse sur le téléphone.",
                        style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
                }
                Switch(checked = s.aiEnabled, onCheckedChange = { v -> scope.launch { container.settings.setAiEnabled(v) } })
            }

            OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text("Adresse du serveur Bullylist") }, singleLine = true,
                placeholder = { Text("https://…") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(token, { token = it }, Modifier.fillMaxWidth(), label = { Text("Jeton d'application (facultatif)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation())
            Text("La clé API du fournisseur IA reste sur le serveur : elle n'est jamais saisie ni stockée dans l'application.",
                style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { scope.launch { container.settings.setAiServer(url, token); test = "Enregistré." } }) { Text("Enregistrer") }
                OutlinedButton(onClick = { scope.launch { test = "Test en cours…"; test = health(url) } }) { Text("Tester la connexion") }
            }
            test?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("Serveur joignable")) BrrPalette.Green else BrrPalette.Amber) }
            if (url.startsWith("http://")) Text("Adresse non chiffrée (http) : acceptée seulement par la version de test de l'application, pour un serveur sur ton réseau local.",
                style = MaterialTheme.typography.bodySmall, color = BrrPalette.Amber)
            Spacer(Modifier.height(60.dp))
        }
    }
}

private suspend fun health(url: String): String = withContext(Dispatchers.IO) {
    runCatching {
        val c = URL(url.trimEnd('/') + "/api/health").openConnection() as HttpURLConnection
        c.connectTimeout = 8000; c.readTimeout = 8000
        try {
            val body = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            "Serveur joignable · modèle ${body.optString("model")}" + if (body.optBoolean("auth")) " · jeton exigé" else ""
        } finally { c.disconnect() }
    }.getOrElse { "Serveur injoignable : vérifie l'adresse et que le serveur est lancé." }
}
