package com.brr.app.ui.alarm

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.reminder.ReminderContent
import kotlinx.coroutines.delay

private val Crimson = Color(0xFF8A0010)
private val Blood = Color(0xFF4A0008)
private val Hot = Color(0xFFFF2D3D)
private val Ember = Color(0xFFFF6A1A)

@Composable
fun AlarmScreen(
    content: ReminderContent,
    onDone: () -> Unit,
    onSnooze: (Int) -> Unit,
    onTomorrow: () -> Unit,
    onDisable: () -> Unit,
) {
    val brr = content.level == ReminderLevel.BRR
    val t = rememberInfiniteTransition(label = "alarm")
    // Pulsation lente (< 1 Hz) : spectaculaire sans risque photosensible.
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(if (brr) 1100 else 1800, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse")
    val stripes by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "stripes")
    val shake by t.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 2400
        0f at 0; 0f at 1700; -2.5f at 1780; 2.5f at 1860; -2f at 1940; 1.5f at 2020; 0f at 2100
    }), label = "shake")

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(250) } }

    val base = if (brr) Hot else Ember
    Box(
        Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(lerp(Crimson, base, pulse * 0.55f), Blood)))
            .drawBehind {
                // Bandes d'alerte diagonales qui défilent.
                val w = 46.dp.toPx()
                val shift = stripes * w * 2
                val alpha = if (brr) 0.10f else 0.06f
                var x = -size.height - w * 2 + shift
                while (x < size.width + size.height) {
                    val p = Path().apply {
                        moveTo(x, 0f); lineTo(x + w, 0f); lineTo(x + w + size.height, size.height); lineTo(x + size.height, size.height); close()
                    }
                    drawPath(p, Color.Black.copy(alpha = alpha))
                    x += w * 2
                }
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.08f + pulse * 0.08f), Color.Transparent),
                    center = Offset(size.width / 2, size.height * 0.3f), radius = size.width * 0.9f), radius = size.width, center = Offset(size.width / 2, size.height * 0.3f))
            },
    ) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            // Bandeau d'état
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f + 0.6f * pulse)))
                Spacer(Modifier.width(8.dp))
                Text(
                    when {
                        content.isFinal -> "DERNIER AVERTISSEMENT"
                        brr -> "ALERTE NIVEAU 4 · MODE B.R.R."
                        else -> "ALERTE NIVEAU 3 · HARCÈLEMENT"
                    },
                    color = Color.White, style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp,
                )
                Spacer(Modifier.weight(1f))
                if (content.preview) Text("APERÇU", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelSmall)
            }
            Spacer(Modifier.height(36.dp))
            Text("MISSION EN COURS :", color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.titleMedium, letterSpacing = 3.sp)
            Spacer(Modifier.height(8.dp))
            val title = content.taskTitle.uppercase()
            val size = when { title.length < 18 -> 54.sp; title.length < 32 -> 44.sp; title.length < 60 -> 34.sp; else -> 26.sp }
            Text(
                title,
                color = Color.White,
                style = MaterialTheme.typography.displayLarge.copy(fontSize = size, lineHeight = size * 1.02f, fontWeight = FontWeight.Bold),
                modifier = Modifier.rotate(if (brr) shake else 0f).graphicsLayer { scaleX = 1f + pulse * 0.015f; scaleY = scaleX },
            )
            Spacer(Modifier.height(22.dp))
            Text(content.message, color = Color.White, style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Medium))
            if (content.minutesLate > 0) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Cela fait maintenant ${content.minutesLate} minute${if (content.minutesLate > 1) "s" else ""} que tu négocies avec cette tâche.",
                    color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodyLarge,
                )
            }

            Spacer(Modifier.height(32.dp))
            // Compte à rebours
            val next = content.nextAt
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.Black.copy(alpha = 0.28f)).padding(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (next != null) {
                    val remaining = ((next - now) / 1000).coerceAtLeast(0)
                    Text("PROCHAINE RELANCE DANS", color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.labelLarge, letterSpacing = 2.sp)
                    Text(
                        "%02d:%02d".format(remaining / 60, remaining % 60),
                        color = Color.White, fontSize = 72.sp, fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"),
                    )
                } else {
                    Text("PLUS AUCUNE RELANCE", color = Color.White, style = MaterialTheme.typography.titleLarge)
                    Text("À partir de maintenant, c'est entre toi et ta conscience.", color = Color.White.copy(alpha = 0.75f),
                        style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                }
            }

            Spacer(Modifier.height(28.dp))
            Button(
                onClick = onDone, modifier = Modifier.fillMaxWidth().height(68.dp), shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Crimson),
            ) { Text("C'EST FAIT ✓", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold)) }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = { onSnooze(5) }, modifier = Modifier.fillMaxWidth().height(56.dp), shape = RoundedCornerShape(18.dp),
                border = BorderStroke(2.dp, Color.White.copy(alpha = 0.8f)),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
            ) { Text("5 MINUTES, PROMIS", style = MaterialTheme.typography.titleMedium) }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                SmallAction("OK, J'Y VAIS\n15 min", Modifier.weight(1f)) { onSnooze(15) }
                SmallAction("PLUS TARD\n1 h", Modifier.weight(1f)) { onSnooze(60) }
                SmallAction("JE REPORTE\ndemain", Modifier.weight(1f), onTomorrow)
            }
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onDisable, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text("Couper les rappels de cette tâche", color = Color.White.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, modifier: Modifier, onClick: () -> Unit) {
    Button(
        onClick = onClick, modifier = modifier.height(60.dp), shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.3f), contentColor = Color.White),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
    ) { Text(label, style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center) }
}

private fun lerp(a: Color, b: Color, f: Float) = androidx.compose.ui.graphics.lerp(a, b, f.coerceIn(0f, 1f))
