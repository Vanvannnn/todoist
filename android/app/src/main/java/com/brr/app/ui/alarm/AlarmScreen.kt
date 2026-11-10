package com.brr.app.ui.alarm

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.reminder.ReminderContent
import com.brr.app.ui.theme.AlertFont
import com.brr.app.ui.theme.AlertShape
import com.brr.app.ui.theme.AlertShapeSmall
import com.brr.app.ui.theme.Body
import com.brr.app.ui.theme.BullyLevels
import com.brr.app.ui.theme.Mono
import kotlinx.coroutines.delay
import kotlin.math.abs

/*
 * Écrans immersifs BULLYLIST — même mise en scène que web/js/ui/alerts.js.
 * Niveau 3 « Bully Mode » : variantes Mission, Radar, Contrôle.
 * Niveau 4 « Nuclear » : variantes Alerte nationale, DEFCON, Flash spécial.
 * Aucun clignotement au-delà de 1 Hz ; effets coupés si les animations système sont désactivées.
 */

private val Hazard = Color(0xFFFFD400)
private val L3 = BullyLevels.base[2]
private val L4 = BullyLevels.base[3]

enum class AlarmVariant { MISSION, RADAR, CONTROLE, NATIONALE, DEFCON, FLASH }

fun variantFor(c: ReminderContent): AlarmVariant {
    val seed = abs((c.taskId * 31 + c.minutesLate / 5 + c.snoozeCount).toInt())
    return if (c.level == ReminderLevel.BRR) listOf(AlarmVariant.NATIONALE, AlarmVariant.DEFCON, AlarmVariant.FLASH)[seed % 3]
    else listOf(AlarmVariant.MISSION, AlarmVariant.RADAR, AlarmVariant.CONTROLE)[seed % 3]
}

fun missionNumber(taskId: Long): String = "%05d".format(abs(taskId * 7919 + 428) % 99991)

@Composable
fun AlarmScreen(
    content: ReminderContent,
    onDone: () -> Unit,
    onSnooze: (Int) -> Unit,
    onTomorrow: () -> Unit,
    onDisable: () -> Unit,
    variant: AlarmVariant = remember(content) { variantFor(content) },
) {
    val ctx = LocalContext.current
    val motion = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(500) } }
    val shownAt = remember(content) { System.currentTimeMillis() }
    // Le retard continue de grimper tant que l'écran est affiché.
    val late = content.minutesLate + (now - shownAt) / 60_000
    val messages = remember(content) { (listOf(content.message) + content.messages).filter { it.isNotBlank() }.distinct() }
    if (content.level == ReminderLevel.BRR) Nuclear(content, variant, late, messages, motion, now, onDone, onSnooze, onTomorrow, onDisable)
    else BullyMode(content, variant, late, messages, motion, now, onDone, onSnooze, onTomorrow, onDisable)
}

// ---------------------------------------------------------------- Niveau 3

@Composable
private fun BullyMode(
    c: ReminderContent, variant: AlarmVariant, late: Long, messages: List<String>, motion: Boolean, now: Long,
    onDone: () -> Unit, onSnooze: (Int) -> Unit, onTomorrow: () -> Unit, onDisable: () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "l3")
    val sweep by t.animateFloat(0f, 360f, infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "sweep")
    Box(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF5A1A05), BullyLevels.deep[2], Color(0xFF0C0402)), center = Offset(540f, 380f), radius = 1500f))
            .drawBehind {
                if (variant == AlarmVariant.RADAR) radar(if (motion) sweep else 40f) else grid(L3.copy(alpha = 0.10f), 30.dp.toPx())
                scanlines()
            },
    ) {
        AlertColumn(
            top = { StatusBar("BULLY MODE · NIVEAU 3", "#${missionNumber(c.taskId)}", L3, motion, c.preview) },
        ) {
            Kicker(when (variant) { AlarmVariant.RADAR -> "CIBLE VERROUILLÉE"; AlarmVariant.CONTROLE -> "CONTRÔLE DE MISSION"; else -> if (c.isFinal) "DERNIER AVERTISSEMENT" else "OPÉRATION EN COURS" })
            TaskTitle(c.taskTitle, motion, big = true)
            Spacer(Modifier.height(18.dp))
            when (variant) {
                AlarmVariant.MISSION -> CountdownRing(c.nextAt, now)
                AlarmVariant.CONTROLE -> Gauges(listOf(late.toInt() to "minutes de retard", 3 to "% de motivation détectée", c.snoozeCount to "reports"), motion)
                else -> Gauges(listOf(late.toInt() to "min de retard", -1 to "avant relance", c.snoozeCount to "excuses"), motion, c.nextAt, now)
            }
            Quote(messages, motion, Color.White)
            Spacer(Modifier.height(22.dp))
            AlertButton("C'EST FAIT", L3, Color(0xFF1A0700), onDone)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GhostButton("5 MIN, PROMIS", L3, Modifier.weight(1f)) { onSnooze(5) }
                GhostButton("JE REPORTE À DEMAIN", L3, Modifier.weight(1f), onTomorrow)
            }
            MoreActions(onSnooze, onDisable)
        }
    }
}

// ---------------------------------------------------------------- Niveau 4

@Composable
private fun Nuclear(
    c: ReminderContent, variant: AlarmVariant, late: Long, messages: List<String>, motion: Boolean, now: Long,
    onDone: () -> Unit, onSnooze: (Int) -> Unit, onTomorrow: () -> Unit, onDisable: () -> Unit,
) {
    val t = rememberInfiniteTransition(label = "l4")
    // Respiration à 1 Hz maximum (sécurité photosensible).
    val breathe by t.animateFloat(0.72f, 1f, infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")
    val stripe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing)), label = "stripe")
    val flash = remember { Animatable(if (motion) 0.85f else 0f) }
    LaunchedEffect(Unit) { if (motion) repeat(2) { flash.snapTo(0.85f); flash.animateTo(0f, tween(400)); delay(600) } }

    Box(Modifier.fillMaxSize().background(Color(0xFF160105))) {
        Box(Modifier.fillMaxSize().alpha(if (motion) breathe else 1f)
            .background(Brush.radialGradient(listOf(Color(0xFF8D0016), Color(0xFF3A0009), Color(0xFF0E0003)), center = Offset(540f, 520f), radius = 1600f)))
        Box(Modifier.fillMaxSize().drawBehind { scanlines() })
        Column(Modifier.fillMaxSize()) {
            if (variant == AlarmVariant.FLASH) TvBar(Modifier.statusBarsPadding()) else HazardStripe(Modifier.statusBarsPadding(), if (motion) stripe else 0f)
            Box(Modifier.weight(1f)) {
                AlertColumn(top = { StatusBar("BULLY MODE: NUCLEAR", "NIVEAU 4", Color(0xFFFF8C9A), motion, c.preview) }, insets = false) {
                    val title = when {
                        c.isFinal -> "DERNIER\nAVERTISSEMENT"
                        variant == AlarmVariant.DEFCON -> "DEFCON 1"
                        variant == AlarmVariant.FLASH -> "URGENCE\nABSOLUE"
                        else -> "ALERTE\nNATIONALE"
                    }
                    Text(title, color = Color.White, modifier = Modifier.semantics { heading() },
                        style = TextStyle(fontFamily = AlertFont, fontWeight = FontWeight.Black, fontSize = 60.sp, lineHeight = 52.sp,
                            shadow = androidx.compose.ui.graphics.Shadow(L4.copy(alpha = 0.8f), blurRadius = 40f)))
                    if (variant == AlarmVariant.DEFCON) Defcon(motion)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.07f)).border(1.dp, L4.copy(alpha = 0.55f)).padding(horizontal = 12.dp, vertical = 9.dp),
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        MonoText("MISSION #${missionNumber(c.taskId)}", Color.White, 13.sp)
                        MonoText(if (c.preview) "EXERCICE" else "PRIORITÉ ABSOLUE", Color.White, 13.sp)
                    }
                    Spacer(Modifier.height(16.dp))
                    TaskTitle(c.taskTitle, motion, big = false)
                    Spacer(Modifier.height(10.dp))
                    LateCounter(late, motion)
                    Quote(messages, motion, Color.White, bar = L4)
                    Spacer(Modifier.height(22.dp))
                    AlertButton("JE PASSE À L'ACTION", Color.White, Color(0xFF9B0018), onDone)
                    Spacer(Modifier.height(10.dp))
                    GhostButton("ACCORDER 5 MINUTES", Color.White, Modifier.fillMaxWidth()) { onSnooze(5) }
                    TextButton(onClick = onTomorrow, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                        Text("JE SUIS UN CAS DÉSESPÉRÉ", color = Color(0xFFFF9AA6), textDecoration = TextDecoration.Underline,
                            style = TextStyle(fontFamily = AlertFont, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp))
                    }
                    MoreActions(onSnooze, onDisable)
                }
            }
            if (variant == AlarmVariant.FLASH) Ticker(messages, c.taskTitle, motion, Modifier.navigationBarsPadding())
            else HazardStripe(Modifier.navigationBarsPadding(), if (motion) stripe else 0f)
        }
        Box(Modifier.fillMaxSize().background(L4.copy(alpha = flash.value)))
    }
}

// ---------------------------------------------------------------- Briques

@Composable
private fun AlertColumn(top: @Composable () -> Unit, insets: Boolean = true, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().then(if (insets) Modifier.statusBarsPadding().navigationBarsPadding() else Modifier)) {
        Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) { top() }
        BoxWithConstraints(Modifier.weight(1f)) {
            // Contenu ancré en bas quand il y a de la place, défilable sur les petits écrans.
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(horizontal = 24.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Bottom, content = content,
            )
        }
    }
}

@Composable
private fun StatusBar(left: String, right: String, color: Color, motion: Boolean, preview: Boolean) {
    val t = rememberInfiniteTransition(label = "dot")
    val on by t.animateFloat(1f, 0.15f, infiniteRepeatable(keyframes { durationMillis = 1000; 1f at 0; 1f at 499; 0.15f at 500; 0.15f at 999 }), label = "blink")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(color.copy(alpha = if (motion) on else 1f)))
        Spacer(Modifier.width(8.dp))
        MonoText(left, color, 12.sp)
        Spacer(Modifier.weight(1f))
        MonoText(if (preview) "APERÇU · $right" else right, color, 12.sp)
    }
}

@Composable
private fun Kicker(text: String) = MonoText(text, Color.White.copy(alpha = 0.8f), 13.sp)

@Composable
private fun MonoText(text: String, color: Color, size: TextUnit, weight: FontWeight = FontWeight.Bold) =
    Text(text, color = color, style = TextStyle(fontFamily = Mono, fontWeight = weight, fontSize = size, letterSpacing = 0.06.sp))

@Composable
private fun TaskTitle(title: String, motion: Boolean, big: Boolean) {
    val t = rememberInfiniteTransition(label = "shake")
    val shake by t.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 3000; 0f at 0; 0f at 2580; -2.5f at 2640; 2.5f at 2700; -2f at 2760; 1.5f at 2820; 0f at 2880
    }), label = "shake")
    val up = title.uppercase()
    val size = when { up.length < 16 -> if (big) 64.sp else 50.sp; up.length < 30 -> if (big) 50.sp else 40.sp; up.length < 60 -> 36.sp; else -> 26.sp }
    Text(up, color = Color.White, modifier = Modifier.padding(top = 4.dp).offset(x = if (motion) shake.dp else 0.dp).rotate(if (motion) shake / 6 else 0f),
        style = TextStyle(fontFamily = AlertFont, fontWeight = FontWeight.Black, fontSize = size, lineHeight = size * 0.92f))
}

@Composable
private fun Quote(messages: List<String>, motion: Boolean, color: Color, bar: Color? = null) {
    var index by remember { mutableIntStateOf(0) }
    var shown by remember { mutableStateOf("") }
    LaunchedEffect(messages) {
        while (true) {
            val full = "« ${messages.getOrElse(index) { "" }} »"
            if (motion) for (i in 1..full.length) { shown = full.take(i); delay(22) } else shown = full
            if (messages.size < 2) break
            delay(6500)
            index = (index + 1) % messages.size
        }
    }
    Row(Modifier.padding(top = 18.dp).heightIn(min = 64.dp)) {
        if (bar != null) { Box(Modifier.width(3.dp).height(56.dp).background(bar)); Spacer(Modifier.width(12.dp)) }
        Text(shown, color = color, style = TextStyle(fontFamily = Body, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp))
    }
}

@Composable
private fun LateCounter(late: Long, motion: Boolean) {
    val shown = remember { Animatable(if (motion) 0f else late.toFloat()) }
    LaunchedEffect(late) { shown.animateTo(late.toFloat(), tween(if (shown.value == 0f && motion) 1400 else 300)) }
    val v = shown.value.toLong()
    Row(verticalAlignment = Alignment.Bottom) {
        MonoText("STATUT : ÉCHEC DEPUIS ", Hazard, 14.sp, FontWeight.ExtraBold)
        MonoText("$v", Hazard, 19.sp, FontWeight.ExtraBold)
        MonoText(if (v > 1) " MINUTES" else " MINUTE", Hazard, 14.sp, FontWeight.ExtraBold)
    }
}

@Composable
private fun CountdownRing(nextAt: Long?, now: Long) {
    val total = remember(nextAt) { ((nextAt ?: now) - now).coerceAtLeast(1) }
    val left = ((nextAt ?: now) - now).coerceAtLeast(0)
    Box(Modifier.size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 10.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.12f), style = Stroke(s), radius = size.minDimension / 2 - s)
            drawArc(L3, -90f, 360f * left / total, false, style = Stroke(s),
                topLeft = Offset(s, s), size = androidx.compose.ui.geometry.Size(size.width - 2 * s, size.height - 2 * s))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            MonoText(if (nextAt == null) "--:--" else "%02d:%02d".format(left / 60000, left / 1000 % 60), Color.White, 24.sp, FontWeight.ExtraBold)
            MonoText(if (nextAt == null) "FIN" else "RELANCE", Color.White.copy(alpha = 0.7f), 10.sp)
        }
    }
}

@Composable
private fun Gauges(values: List<Pair<Int, String>>, motion: Boolean, nextAt: Long? = null, now: Long = 0) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((v, label) in values) {
            Column(Modifier.weight(1f).clip(AlertShapeSmall).background(L3.copy(alpha = 0.08f)).border(1.dp, L3.copy(alpha = 0.4f), AlertShapeSmall).padding(10.dp)) {
                if (v < 0) {
                    val left = ((nextAt ?: now) - now).coerceAtLeast(0)
                    MonoText(if (nextAt == null) "--:--" else "%02d:%02d".format(left / 60000, left / 1000 % 60), Color.White, 22.sp, FontWeight.ExtraBold)
                } else {
                    val a = remember { Animatable(if (motion) 0f else v.toFloat()) }
                    LaunchedEffect(v) { a.animateTo(v.toFloat(), tween(900)) }
                    MonoText("${a.value.toInt()}", Color.White, 22.sp, FontWeight.ExtraBold)
                }
                Spacer(Modifier.height(4.dp))
                Text(label, color = Color.White.copy(alpha = 0.75f), style = TextStyle(fontFamily = Body, fontSize = 11.sp, lineHeight = 13.sp))
            }
        }
    }
}

@Composable
private fun Defcon(motion: Boolean) {
    var on by remember { mutableIntStateOf(if (motion) 0 else 4) }
    LaunchedEffect(Unit) { if (motion) for (i in 0..4) { on = i; delay(420) } }
    Row(Modifier.padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0..4) {
            val active = i == on
            Box(Modifier.weight(1f).background(if (active) L4 else Color.White.copy(alpha = 0.06f)).padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                MonoText("${5 - i}", if (active) Color.White else Color.White.copy(alpha = 0.35f), 16.sp, FontWeight.ExtraBold)
            }
        }
    }
}

@Composable
private fun AlertButton(label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Button(onClick = onClick, modifier = Modifier.fillMaxWidth().height(64.dp), shape = AlertShape,
        colors = ButtonDefaults.buttonColors(containerColor = bg, contentColor = fg)) {
        Text(label, style = TextStyle(fontFamily = AlertFont, fontWeight = FontWeight.Black, fontSize = 22.sp))
    }
}

@Composable
private fun GhostButton(label: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = modifier.height(54.dp), shape = AlertShapeSmall,
        border = BorderStroke(1.5.dp, color.copy(alpha = 0.6f)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = color.copy(alpha = 0.1f), contentColor = Color.White)) {
        Text(label, textAlign = TextAlign.Center, style = TextStyle(fontFamily = AlertFont, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, lineHeight = 17.sp))
    }
}

/** Les actions de la V1 restent disponibles : +15 min, +1 h, couper les rappels de la tâche. */
@Composable
private fun MoreActions(onSnooze: (Int) -> Unit, onDisable: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
        TextButton(onClick = { onSnooze(15) }) { Text("+15 min", color = Color.White.copy(alpha = 0.65f)) }
        TextButton(onClick = { onSnooze(60) }) { Text("+1 h", color = Color.White.copy(alpha = 0.65f)) }
        TextButton(onClick = onDisable) { Text("Couper les rappels", color = Color.White.copy(alpha = 0.55f)) }
    }
}

@Composable
private fun HazardStripe(modifier: Modifier, phase: Float) {
    Canvas(modifier.fillMaxWidth().height(26.dp)) {
        val w = 15.dp.toPx(); val shift = phase * w * 2
        drawRect(Color(0xFF0D0D0D))
        clipRect {
            var x = -size.height - w * 2 + shift
            while (x < size.width + size.height) {
                drawPath(Path().apply { moveTo(x, size.height); lineTo(x + w, size.height); lineTo(x + w + size.height, 0f); lineTo(x + size.height, 0f); close() }, Hazard)
                x += w * 2
            }
        }
    }
}

@Composable
private fun TvBar(modifier: Modifier) {
    Row(modifier.fillMaxWidth()) {
        Text("EN DIRECT", color = Color(0xFFC4001D), modifier = Modifier.background(Color.White).padding(horizontal = 12.dp, vertical = 7.dp),
            style = TextStyle(fontFamily = AlertFont, fontWeight = FontWeight.Black, fontSize = 16.sp))
        Text("FLASH SPÉCIAL", color = Color.White, modifier = Modifier.weight(1f).background(Color(0xFFC4001D)).padding(horizontal = 12.dp, vertical = 7.dp),
            style = TextStyle(fontFamily = AlertFont, fontWeight = FontWeight.Black, fontSize = 16.sp))
    }
}

@Composable
private fun Ticker(messages: List<String>, task: String, motion: Boolean, modifier: Modifier) {
    val text = (messages.map { "+++ $it " } + "+++ ${task.uppercase()} : TOUJOURS PAS FAIT ").joinToString("")
    val t = rememberInfiniteTransition(label = "ticker")
    val x by t.animateFloat(0f, 1f, infiniteRepeatable(tween(22000, easing = LinearEasing)), label = "x")
    BoxWithConstraints(modifier.fillMaxWidth().background(Hazard).padding(vertical = 7.dp)) {
        val w = maxWidth
        Text(text, maxLines = 1, softWrap = false, color = Color(0xFF111111),
            modifier = Modifier.offset(x = if (motion) w - (w * 6) * x else 0.dp),
            style = TextStyle(fontFamily = AlertFont, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp))
    }
}

private fun DrawScope.grid(color: Color, step: Float) {
    var x = 0f
    while (x < size.width) { drawLine(color, Offset(x, 0f), Offset(x, size.height * 0.75f)); x += step }
    var y = 0f
    while (y < size.height * 0.75f) { drawLine(color, Offset(0f, y), Offset(size.width, y)); y += step }
}

private fun DrawScope.radar(angle: Float) {
    val c = Offset(size.width * 0.82f, size.height * 0.26f); val r = size.width * 0.62f
    for (i in 1..4) drawCircle(L3.copy(alpha = 0.22f), radius = r * i / 4, center = c, style = Stroke(1.dp.toPx()))
    drawArc(Brush.sweepGradient(listOf(Color.Transparent, L3.copy(alpha = 0.45f)), center = c), angle - 70f, 70f, true,
        topLeft = Offset(c.x - r, c.y - r), size = androidx.compose.ui.geometry.Size(r * 2, r * 2))
}

private fun DrawScope.scanlines() {
    var y = 0f; val step = 3.dp.toPx()
    while (y < size.height) { drawLine(Color.White.copy(alpha = 0.025f), Offset(0f, y), Offset(size.width, y)); y += step }
}
