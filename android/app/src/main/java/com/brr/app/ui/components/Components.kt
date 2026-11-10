package com.brr.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brr.app.data.db.TaskWithDetails
import com.brr.app.domain.Bucket
import com.brr.app.domain.TaskBuckets
import com.brr.app.domain.model.Priority
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.domain.model.RepeatType
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette
import java.time.ZoneId

/** Case ronde animée, colorée selon la priorité (à la Todoist). */
@Composable
fun CheckCircle(checked: Boolean, color: Color, onToggle: () -> Unit, size: Dp = 24.dp) {
    val haptic = LocalHapticFeedback.current
    val fill by animateColorAsState(if (checked) color else Color.Transparent, label = "fill")
    val scale by animateFloatAsState(if (checked) 1f else 0.95f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "scale")
    Box(
        Modifier
            .size(size + 16.dp)
            .clip(CircleShape)
            .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onToggle() },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(size).scale(scale).clip(CircleShape)
                .background(if (checked) fill else color.copy(alpha = 0.10f))
                .border(1.8.dp, color, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (checked) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(size * 0.65f))
        }
    }
}

@Composable
fun Pill(text: String, color: Color, icon: ImageVector? = null, filled: Boolean = false) {
    Row(
        Modifier.clip(RoundedCornerShape(50))
            .background(if (filled) color.copy(alpha = 0.16f) else Color.Transparent)
            .padding(horizontal = if (filled) 8.dp else 0.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(3.dp))
        }
        Text(text, color = color, style = MaterialTheme.typography.labelMedium, maxLines = 1)
    }
}

@Composable
fun LevelBadge(level: ReminderLevel, compact: Boolean = false) {
    val c = Brr.colors.level(level)
    Pill(if (compact) level.label.uppercase().take(if (level == ReminderLevel.BRR) 6 else 4) else level.label, c,
        if (level == ReminderLevel.BRR) BrrIcons.Bolt else BrrIcons.Alarm, filled = true)
}

@Composable
fun TaskRow(
    item: TaskWithDetails,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    now: Long = System.currentTimeMillis(),
) {
    val t = item.task
    val colors = Brr.colors
    val pColor = colors.priority(t.priority)
    Row(
        modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).clickable(onClick = onClick)
            .padding(start = 8.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        CheckCircle(t.completed, pColor, onToggle, size = 22.dp)
        Column(Modifier.weight(1f).padding(top = 9.dp)) {
            Text(
                t.title,
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                color = if (t.completed) colors.muted else MaterialTheme.colorScheme.onBackground,
                textDecoration = if (t.completed) TextDecoration.LineThrough else null,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (t.description.isNotBlank()) {
                Text(t.description, style = MaterialTheme.typography.bodySmall, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                t.dueAt?.let { due ->
                    val overdue = !t.completed && TaskBuckets.bucketOf(due, t.hasTime, now, ZoneId.systemDefault()) == Bucket.OVERDUE
                    Pill(Fmt.due(due, t.hasTime, now), if (overdue) BrrPalette.Red else colors.muted, BrrIcons.Today)
                }
                if (t.repeatType != RepeatType.NONE) Icon(BrrIcons.Repeat, null, tint = colors.muted, modifier = Modifier.size(13.dp))
                if (item.subtasks.isNotEmpty()) {
                    Pill("${item.subtasks.count { it.done }}/${item.subtasks.size}", colors.muted, BrrIcons.Checklist)
                }
                if (t.snoozedUntil != null && !t.completed) Pill(Fmt.time(t.snoozedUntil), BrrPalette.Amber, BrrIcons.Alarm)
                if (t.dueAt != null && t.remindersEnabled && t.reminderLevel.ordinal >= ReminderLevel.HARCELEMENT.ordinal && !t.completed) {
                    LevelBadge(t.reminderLevel, compact = true)
                }
                Spacer(Modifier.weight(1f))
                item.project?.let { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.name, style = MaterialTheme.typography.labelMedium, color = colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(end = 5.dp))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(Color(p.color)))
                    }
                }
            }
        }
    }
}

@Composable
fun SectionHeader(title: String, count: Int? = null, color: Color = MaterialTheme.colorScheme.onBackground, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.background).padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = color)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = Brr.colors.muted)
        }
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

@Composable
fun EmptyState(title: String, subtitle: String, icon: ImageVector = BrrIcons.Checklist, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = 40.dp, vertical = 56.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp)) }
        Spacer(Modifier.height(18.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(6.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable
fun BrrCard(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(16.dp), onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(shape).background(Brr.colors.card).border(1.dp, Brr.colors.hairline, shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Petite puce sélectionnable à l'identité Bullylist */
@Composable
fun ChoiceChip(text: String, selected: Boolean, onClick: () -> Unit, color: Color = MaterialTheme.colorScheme.primary, icon: ImageVector? = null) {
    val bg by animateColorAsState(if (selected) color.copy(alpha = 0.18f) else Brr.colors.cardHigh, label = "chipbg")
    val fg by animateColorAsState(if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant, label = "chipfg")
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(bg)
            .border(1.dp, if (selected) color.copy(alpha = 0.6f) else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = fg, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
        Text(text, color = fg, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
fun SettingRow(title: String, subtitle: String? = null, icon: ImageVector? = null, onClick: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
        }
        trailing()
    }
}

fun priorityLabelShort(p: Priority) = when (p) { Priority.URGENT -> "P1"; Priority.HIGH -> "P2"; Priority.NORMAL -> "P3"; Priority.LOW -> "P4" }
