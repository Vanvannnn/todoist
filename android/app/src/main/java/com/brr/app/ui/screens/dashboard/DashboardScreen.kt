package com.brr.app.ui.screens.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.brr.app.AppContainer
import com.brr.app.data.db.TaskWithDetails
import com.brr.app.domain.Bucket
import com.brr.app.domain.TaskBuckets
import com.brr.app.domain.model.HistoryType
import com.brr.app.punchline.PunchlineCatalog
import com.brr.app.reminder.PermissionSnapshot
import com.brr.app.ui.components.BrrCard
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.components.Fmt
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale

data class DashboardState(
    val loading: Boolean = true,
    val overdue: Int = 0,
    val today: Int = 0,
    val open: Int = 0,
    val completedWeek: Int = 0,
    val snoozesWeek: Int = 0,
    val perDay: List<Pair<String, Int>> = emptyList(),
    val upcoming: List<TaskWithDetails> = emptyList(),
) {
    /** 0 = machine de guerre, 100 = légende du canapé. */
    val procrastination: Int get() = (30 + overdue * 12 + snoozesWeek * 4 - completedWeek * 3).coerceIn(0, 100)
}

class DashboardViewModel(c: AppContainer) : ViewModel() {
    private val zone = ZoneId.systemDefault()
    private val weekStart = LocalDate.now(zone).minusDays(6).atStartOfDay(zone).toInstant().toEpochMilli()
    private val ticker = flow { while (true) { emit(System.currentTimeMillis()); delay(60_000) } }

    val state: StateFlow<DashboardState> = combine(c.tasks.observeOpen(), c.tasks.observeHistorySince(weekStart), ticker) { open, hist, now ->
        val buckets = open.map { TaskBuckets.bucketOf(it.task.dueAt, it.task.hasTime, now, zone) }
        val completions = hist.filter { it.type == HistoryType.COMPLETED }
        val today = LocalDate.now(zone)
        val perDay = (6 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            d.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.FRENCH) to
                completions.count { Instant.ofEpochMilli(it.at).atZone(zone).toLocalDate() == d }
        }
        DashboardState(
            loading = false,
            overdue = buckets.count { it == Bucket.OVERDUE },
            today = buckets.count { it == Bucket.TODAY },
            open = open.size,
            completedWeek = completions.size,
            snoozesWeek = hist.count { it.type == HistoryType.SNOOZED },
            perDay = perDay,
            upcoming = open.filter { it.task.nextReminderAt != null }.sortedBy { it.task.nextReminderAt }.take(5),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DashboardState())
}

@Composable
fun DashboardScreen(container: AppContainer, onOpenTask: (Long) -> Unit, onHistory: () -> Unit, onPermissions: () -> Unit) {
    val vm = brrViewModel { DashboardViewModel(container) }
    val s by vm.state.collectAsStateWithLifecycle()
    var perms by remember { mutableStateOf<PermissionSnapshot?>(null) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { perms = container.permissions.snapshot() }
    val punch = remember {
        val all = PunchlineCatalog.all
        all[(LocalDate.now().toEpochDay() % all.size).toInt()]
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).verticalScroll(rememberScrollState()).statusBarsPadding().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(20.dp))
        Text("Tableau de bord", style = MaterialTheme.typography.displaySmall)
        Text("Voici l'étendue des dégâts.", style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted)
        Spacer(Modifier.height(20.dp))

        perms?.takeIf { !it.allGood }?.let {
            BrrCard(Modifier.fillMaxWidth(), onClick = onPermissions) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(BrrIcons.Shield, null, tint = BrrPalette.Amber)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Rappels potentiellement bridés", style = MaterialTheme.typography.titleMedium)
                        Text("Certaines autorisations manquent. Touchez pour corriger.", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
                    }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brr.colors.muted)
                }
            }
            Spacer(Modifier.height(14.dp))
        }

        ProcrastinationGauge(s.procrastination)
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("En retard", s.overdue, if (s.overdue > 0) BrrPalette.Red else Brr.colors.muted, Modifier.weight(1f))
            Stat("Aujourd'hui", s.today, MaterialTheme.colorScheme.onBackground, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat("Terminées (7 j)", s.completedWeek, Brr.colors.success, Modifier.weight(1f))
            Stat("Reports (7 j)", s.snoozesWeek, BrrPalette.Amber, Modifier.weight(1f))
        }

        Spacer(Modifier.height(14.dp))
        BrrCard(Modifier.fillMaxWidth()) {
            Text("Tâches terminées cette semaine", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(14.dp))
            val max = (s.perDay.maxOfOrNull { it.second } ?: 0).coerceAtLeast(1)
            Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
                s.perDay.forEachIndexed { i, (label, n) ->
                    val frac by animateFloatAsState(n.toFloat() / max, tween(700, delayMillis = i * 60), label = "bar")
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                        if (n > 0) Text("$n", style = MaterialTheme.typography.labelSmall, color = Brr.colors.muted)
                        Box(
                            Modifier.padding(top = 4.dp).width(18.dp).height((80 * frac).dp.coerceAtLeast(4.dp)).clip(RoundedCornerShape(6.dp))
                                .background(if (i == s.perDay.lastIndex) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = Brr.colors.muted)
                    }
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        BrrCard(Modifier.fillMaxWidth()) {
            Text("PUNCHLINE DU JOUR", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text("« ${punch.text} »", style = MaterialTheme.typography.titleLarge)
            Text(punch.category.label, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted, modifier = Modifier.padding(top = 6.dp))
        }

        Spacer(Modifier.height(14.dp))
        BrrCard(Modifier.fillMaxWidth()) {
            Text("Prochains rappels", style = MaterialTheme.typography.titleMedium)
            if (s.upcoming.isEmpty()) Text("Aucun rappel programmé. Calme plat.", style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted, modifier = Modifier.padding(top = 8.dp))
            s.upcoming.forEach { t ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { onOpenTask(t.task.id) }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(Brr.colors.level(t.task.reminderLevel)))
                    Spacer(Modifier.width(10.dp))
                    Text(t.task.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                    t.task.nextReminderAt?.let { Text(Fmt.relative(it), style = MaterialTheme.typography.labelMedium, color = Brr.colors.muted) }
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brr.colors.faint)
                }
            }
        }

        Spacer(Modifier.height(14.dp))
        BrrCard(Modifier.fillMaxWidth(), onClick = onHistory) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(BrrIcons.History, null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Historique et tâches terminées", style = MaterialTheme.typography.titleMedium)
                    Text("Tes exploits (et tes reports).", style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Brr.colors.muted)
            }
        }
        Spacer(Modifier.height(120.dp))
    }
}

@Composable
private fun Stat(label: String, value: Int, color: Color, modifier: Modifier) {
    BrrCard(modifier) {
        Text(value.toString(), style = MaterialTheme.typography.displaySmall, color = color)
        Text(label, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
    }
}

@Composable
private fun ProcrastinationGauge(score: Int) {
    val anim by animateFloatAsState(score / 100f, tween(900), label = "gauge")
    val (label, sub) = when {
        score <= 20 -> "Machine de guerre" to "Rien ne te résiste. B.R.R. s'ennuie un peu."
        score <= 40 -> "Plutôt responsable" to "Quelques écarts, mais on te fait confiance. Presque."
        score <= 60 -> "Négociateur" to "Tu négocies beaucoup avec tes tâches."
        score <= 80 -> "Procrastinateur confirmé" to "Le canapé t'a nommé employé du mois."
        else -> "Légende du canapé" to "Niveau d'alerte maximal. Le mode B.R.R. a été conçu pour toi."
    }
    val track = Brr.colors.cardHigh
    val color = when { score <= 40 -> Brr.colors.success; score <= 60 -> BrrPalette.Amber; score <= 80 -> BrrPalette.Orange; else -> BrrPalette.Red }
    BrrCard(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(96.dp)) {
                    val stroke = 10.dp.toPx()
                    val sz = Size(size.width - stroke, size.height - stroke)
                    val tl = Offset(stroke / 2, stroke / 2)
                    drawArc(track, 135f, 270f, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                    drawArc(color, 135f, 270f * anim, false, tl, sz, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text("$score", style = MaterialTheme.typography.headlineMedium, color = color)
            }
            Spacer(Modifier.width(16.dp))
            Column {
                Text("INDICE DE PROCRASTINATION", style = MaterialTheme.typography.labelSmall, color = Brr.colors.muted)
                Text(label, style = MaterialTheme.typography.titleLarge, color = color)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
            }
        }
    }
}
