package com.brr.app.ui.screens.welcome

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.brr.app.AppContainer
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.ui.screens.settings.PermissionList
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette

@Composable
fun WelcomeScreen(container: AppContainer, onDone: () -> Unit) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val glow by pulse.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Reverse), label = "glow")

    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).drawBehind {
            drawCircle(
                Brush.radialGradient(listOf(BrrPalette.Red.copy(alpha = glow * 0.5f), Color.Transparent), center = Offset(size.width * 0.8f, size.height * 0.1f), radius = size.width),
                radius = size.width, center = Offset(size.width * 0.8f, size.height * 0.1f),
            )
        },
    ) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                repeat(3) { i ->
                    Box(Modifier.height(4.dp).width(if (i == step) 28.dp else 12.dp).clip(RoundedCornerShape(2.dp))
                        .background(if (i <= step) MaterialTheme.colorScheme.primary else Brr.colors.hairline))
                }
                Spacer(Modifier.weight(1f))
                if (step < 2) TextButton(onClick = onDone) { Text("Passer", color = Brr.colors.muted) }
            }
            AnimatedContent(step, transitionSpec = { (slideInHorizontally { it / 3 } + fadeIn()) togetherWith fadeOut() }, label = "step",
                modifier = Modifier.weight(1f)) { s ->
                when (s) {
                    0 -> Intro(glow)
                    1 -> Levels()
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(24.dp))
                        Text("Dernière étape", style = MaterialTheme.typography.headlineLarge)
                        Text("Donne à Bullylist les moyens de te harceler. Tu pourras changer d'avis à tout moment.",
                            style = MaterialTheme.typography.bodyLarge, color = Brr.colors.muted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
                        PermissionList(container)
                    }
                }
            }
            Button(
                onClick = { if (step < 2) step++ else onDone() },
                modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
            ) { Text(if (step < 2) "Continuer" else "C'est parti. Hélas.", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

@Composable
private fun Intro(glow: Float) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        // Logo : carré chanfreiné, point d'exclamation (design/tokens.json).
        Box(Modifier.size(84.dp).scale(0.9f + glow * 0.2f).clip(com.brr.app.ui.theme.AlertShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
            Text("!", style = MaterialTheme.typography.displayMedium, color = MaterialTheme.colorScheme.onPrimary)
        }
        Spacer(Modifier.height(28.dp))
        Text("BULLYLIST", style = MaterialTheme.typography.displayMedium)
        Text("Be Right Responsible", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(20.dp))
        Text(
            "Une appli de tâches élégante, rapide et minimaliste.\n\nAvec un système de rappels qui, lui, ne l'est pas du tout.",
            style = MaterialTheme.typography.bodyLarge, color = Brr.colors.muted,
        )
    }
}

@Composable
private fun Levels() {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Spacer(Modifier.height(24.dp))
        Text("Quatre niveaux de nuisance", style = MaterialTheme.typography.headlineLarge)
        Text("Chaque tâche choisit le sien. Ignore un rappel, et il monte d'un cran.", style = MaterialTheme.typography.bodyLarge,
            color = Brr.colors.muted, modifier = Modifier.padding(top = 8.dp, bottom = 20.dp))
        ReminderLevel.entries.forEach { l ->
            val c = Brr.colors.level(l)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).clip(RoundedCornerShape(16.dp)).background(c.copy(alpha = 0.10f)).padding(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text("${l.ordinal + 1}", style = MaterialTheme.typography.headlineMedium, color = c, modifier = Modifier.width(36.dp))
                Column {
                    Text(l.label, style = MaterialTheme.typography.titleMedium, color = c)
                    Text(l.tagline, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("Saisie rapide : « Sortir les poubelles demain 20h !1 #Maison @brr »", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
    }
}
