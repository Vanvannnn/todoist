package com.brr.app.ui.screens.tasks

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.brr.app.AppContainer
import com.brr.app.data.db.TaskWithDetails
import com.brr.app.domain.Bucket
import com.brr.app.domain.TaskView
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.components.ChoiceChip
import com.brr.app.ui.components.EmptyState
import com.brr.app.ui.components.Fmt
import com.brr.app.ui.components.SectionHeader
import com.brr.app.ui.components.TaskRow
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette
import kotlinx.coroutines.launch

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TasksScreen(
    container: AppContainer,
    snackbar: SnackbarHostState,
    projectId: Long? = null,
    onOpenTask: (Long) -> Unit,
    onOpenEditor: (text: String) -> Unit,
    onBack: (() -> Unit)? = null,
    onEditProject: ((Long) -> Unit)? = null,
    onVoice: (() -> Unit)? = null,
) {
    val vm = brrViewModel(key = "tasks_$projectId") { TasksViewModel(container, projectId) }
    val state by vm.state.collectAsStateWithLifecycle()
    val view by vm.view.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val filters by vm.filters.collectAsStateWithLifecycle()
    var searching by rememberSaveable { mutableStateOf(false) }
    var showQuickAdd by rememberSaveable { mutableStateOf(false) }
    var showFilters by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun complete(t: TaskWithDetails) {
        vm.complete(t.task.id)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val msg = if (t.task.repeatType != com.brr.app.domain.model.RepeatType.NONE) "Occurrence terminée. À la prochaine !" else "« ${t.task.title} » terminée. Enfin."
            val r = snackbar.showSnackbar(msg, actionLabel = "Annuler", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed && t.task.repeatType == com.brr.app.domain.model.RepeatType.NONE) vm.reopen(t.task.id)
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 120.dp)) {
            item(key = "header") {
                Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Retour") }
                        else Spacer(Modifier.width(12.dp))
                        Spacer(Modifier.weight(1f))
                        if (onVoice != null) IconButton(onClick = onVoice) { Icon(BrrIcons.Mic, "Dicter et planifier ma semaine") }
                        IconButton(onClick = { searching = !searching; if (!searching) vm.query.value = "" }) {
                            Icon(if (searching) Icons.Default.Close else Icons.Default.Search, "Rechercher")
                        }
                        IconButton(onClick = { showFilters = true }) {
                            Icon(BrrIcons.Filter, "Filtres", tint = if (filters.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onBackground)
                        }
                        if (projectId != null && onEditProject != null) IconButton(onClick = { onEditProject(projectId) }) { Icon(Icons.Default.Edit, "Modifier le projet") }
                    }
                    Column(Modifier.padding(horizontal = 20.dp)) {
                        Text(
                            state.project?.name ?: view.label,
                            style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onBackground,
                        )
                        Text(
                            if (projectId != null) "${state.sections.sumOf { it.items.size }} tâches ouvertes" else headerSubtitle(view, state),
                            style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted,
                        )
                    }
                    AnimatedVisibility(searching) {
                        val fr = remember { FocusRequester() }
                        LaunchedEffect(Unit) { fr.requestFocus() }
                        TextField(
                            value = query, onValueChange = { vm.query.value = it },
                            placeholder = { Text("Rechercher une tâche, une note, un projet…") },
                            leadingIcon = { Icon(Icons.Default.Search, null) }, singleLine = true,
                            shape = RoundedCornerShape(14.dp),
                            colors = TextFieldDefaults.colors(
                                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                                focusedContainerColor = Brr.colors.cardHigh, unfocusedContainerColor = Brr.colors.cardHigh,
                            ),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).focusRequester(fr),
                        )
                    }
                    if (projectId == null) {
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            TaskView.entries.forEach { tv ->
                                val n = state.counts[tv] ?: 0
                                ChoiceChip(
                                    text = if (n > 0) "${tv.label}  $n" else tv.label,
                                    selected = view == tv, onClick = { vm.view.value = tv },
                                    color = if (tv == TaskView.OVERDUE && n > 0) BrrPalette.Red else MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            if (!state.loading && state.isEmpty) {
                item(key = "empty") {
                    if (query.isNotBlank()) EmptyState("Rien trouvé", "Aucune tâche ne correspond à « $query ».", Icons.Default.Search)
                    else emptyFor(view, projectId != null).let { (t, s) -> EmptyState(t, s) }
                }
            }

            state.sections.forEach { section ->
                val showHeader = projectId != null || view != TaskView.TODAY || state.sections.size > 1 || section.bucket == Bucket.OVERDUE
                if (showHeader) stickyHeader(key = "h_${section.bucket}") {
                    SectionHeader(
                        section.bucket.label, section.items.size,
                        color = if (section.bucket == Bucket.OVERDUE) BrrPalette.Red else MaterialTheme.colorScheme.onBackground,
                    )
                }
                items(section.items, key = { it.task.id }) { item ->
                    SwipeableTaskRow(
                        item = item, now = state.now,
                        onComplete = { complete(item) },
                        onPostpone = {
                            vm.postpone(item.task.id)
                            scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar("Reportée à demain. On en reparle.") }
                        },
                        onClick = { onOpenTask(item.task.id) },
                        modifier = Modifier.animateItem(),
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { showQuickAdd = true },
            containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp),
            modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Default.Add, "Nouvelle tâche", modifier = Modifier.size(28.dp)) }
    }

    if (showQuickAdd) {
        QuickAddSheet(
            projectName = state.project?.name,
            onDismiss = { showQuickAdd = false },
            onSubmit = { vm.quickAdd(it) },
            onDetails = { showQuickAdd = false; onOpenEditor(it) },
        )
    }
    if (showFilters) {
        FilterSheet(
            filters = filters, projects = state.projects, showProjects = projectId == null,
            onChange = { vm.filters.value = it }, onDismiss = { showFilters = false },
        )
    }
}

private fun headerSubtitle(view: TaskView, s: TasksUiState): String {
    val n = s.sections.sumOf { it.items.size }
    val overdue = s.counts[TaskView.OVERDUE] ?: 0
    return when (view) {
        TaskView.TODAY -> Fmt.todayLong() + if (overdue > 0) " · $overdue en retard" else ""
        else -> if (n == 0) "Rien ici" else "$n tâche${if (n > 1) "s" else ""}"
    }
}

private fun emptyFor(view: TaskView, project: Boolean): Pair<String, String> = when {
    project -> "Projet vide" to "Ajoute une première tâche avec le bouton +."
    view == TaskView.TODAY -> "Journée libre. Suspect." to "Rien de prévu aujourd'hui. Profite… ou ajoute quelque chose avec +."
    view == TaskView.TOMORROW -> "Demain est vierge" to "Pour l'instant. On sait tous comment ça finit."
    view == TaskView.WEEK -> "Semaine tranquille" to "Aucune tâche cette semaine. Tu es sûr de ton coup ?"
    view == TaskView.OVERDUE -> "Zéro retard" to "Incroyable. Fais une capture d'écran, ça n'arrivera peut-être plus."
    else -> "Aucune tâche" to "Ajoute ta première mission avec le bouton +."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeableTaskRow(
    item: TaskWithDetails, now: Long, onComplete: () -> Unit, onPostpone: () -> Unit, onClick: () -> Unit, modifier: Modifier,
) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        when (v) {
            SwipeToDismissBoxValue.StartToEnd -> onComplete()
            SwipeToDismissBoxValue.EndToStart -> onPostpone()
            SwipeToDismissBoxValue.Settled -> Unit
        }
        false // l'élément revient en place ; la liste se met à jour d'elle-même
    })
    SwipeToDismissBox(
        state = state, modifier = modifier,
        backgroundContent = {
            val dir = state.dismissDirection
            val (color, icon, label, align) = when (dir) {
                SwipeToDismissBoxValue.StartToEnd -> Quad(Brr.colors.success, Icons.Default.Check, "Terminer", Alignment.CenterStart)
                else -> Quad(BrrPalette.Amber, BrrIcons.Alarm, "Demain", Alignment.CenterEnd)
            }
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)).background(color.copy(alpha = 0.22f)).padding(horizontal = 24.dp), contentAlignment = align) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, null, tint = color)
                    Spacer(Modifier.width(8.dp))
                    Text(label, color = color, style = MaterialTheme.typography.labelLarge)
                }
            }
        },
    ) {
        TaskRow(item, onToggle = onComplete, onClick = onClick, now = now)
    }
    Spacer(Modifier.height(0.dp))
}

private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
