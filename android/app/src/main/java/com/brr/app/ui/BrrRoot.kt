package com.brr.app.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.brr.app.AppContainer
import com.brr.app.data.prefs.AppSettings
import com.brr.app.domain.model.ReminderLevel
import com.brr.app.reminder.InAppAlert
import com.brr.app.reminder.InAppAlerts
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.screens.dashboard.DashboardScreen
import com.brr.app.ui.screens.detail.TaskDetailScreen
import com.brr.app.ui.screens.edit.TaskEditScreen
import com.brr.app.ui.screens.history.HistoryScreen
import com.brr.app.ui.screens.projects.ProjectDialog
import com.brr.app.ui.screens.projects.ProjectsScreen
import com.brr.app.ui.screens.settings.AlarmSettingsScreen
import com.brr.app.ui.screens.settings.PermissionsScreen
import com.brr.app.ui.screens.settings.PunchlinesScreen
import com.brr.app.ui.screens.settings.SettingsScreen
import com.brr.app.ui.screens.tasks.TasksScreen
import com.brr.app.ui.screens.welcome.WelcomeScreen
import com.brr.app.ui.theme.Brr
import com.brr.app.ui.theme.BrrPalette
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    TASKS("tab_tasks", "Tâches", BrrIcons.Checklist),
    DASHBOARD("tab_dashboard", "Bilan", BrrIcons.Dashboard),
    PROJECTS("tab_projects", "Projets", BrrIcons.Folder),
    SETTINGS("tab_settings", "Réglages", Icons.Default.Settings),
}

@Composable
fun BrrRoot(container: AppContainer, settings: AppSettings?, openTaskId: Long?, onTaskOpened: () -> Unit) {
    if (settings == null) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }
    val scope = rememberCoroutineScope()
    if (!settings.onboardingDone) {
        WelcomeScreen(container) { scope.launch { container.settings.setOnboardingDone() } }
        return
    }
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(openTaskId) {
        if (openTaskId != null) { nav.navigate("task/$openTaskId") { launchSingleTop = true }; onTaskOpened() }
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = Tab.entries.any { it.route == route }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = { if (showBar) BottomBar(nav, route) },
        ) { pad ->
            NavHost(nav, startDestination = Tab.TASKS.route, modifier = Modifier.padding(bottom = pad.calculateBottomPadding()).consumeWindowInsets(pad),
                enterTransition = { fadeIn(tween(220)) + slideInHorizontally(tween(260)) { it / 12 } },
                exitTransition = { fadeOut(tween(160)) },
                popEnterTransition = { fadeIn(tween(220)) },
                popExitTransition = { fadeOut(tween(160)) + slideOutHorizontally(tween(220)) { it / 12 } },
            ) {
                composable(Tab.TASKS.route) {
                    TasksScreen(container, snackbar, onOpenTask = { nav.navigate("task/$it") }, onOpenEditor = { nav.navigate(editRoute(text = it)) })
                }
                composable(Tab.DASHBOARD.route) {
                    DashboardScreen(container, onOpenTask = { nav.navigate("task/$it") }, onHistory = { nav.navigate("history") }, onPermissions = { nav.navigate("permissions") })
                }
                composable(Tab.PROJECTS.route) { ProjectsScreen(container, onOpenProject = { nav.navigate("project/$it") }) }
                composable(Tab.SETTINGS.route) {
                    SettingsScreen(container, onAlarms = { nav.navigate("alarms") }, onPunchlines = { nav.navigate("punchlines") },
                        onPermissions = { nav.navigate("permissions") }, onHistory = { nav.navigate("history") })
                }
                composable("task/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    val id = e.arguments?.getLong("id") ?: 0L
                    TaskDetailScreen(container, id, onBack = { nav.popBackStack() }, onEdit = { nav.navigate(editRoute(id = it)) })
                }
                composable(
                    "edit?id={id}&project={project}&text={text}",
                    arguments = listOf(
                        navArgument("id") { type = NavType.LongType; defaultValue = 0L },
                        navArgument("project") { type = NavType.LongType; defaultValue = 0L },
                        navArgument("text") { type = NavType.StringType; nullable = true; defaultValue = null },
                    ),
                ) { e ->
                    val id = e.arguments?.getLong("id") ?: 0L
                    TaskEditScreen(
                        container, id, e.arguments?.getLong("project")?.takeIf { it > 0 }, e.arguments?.getString("text"),
                        onClose = { nav.popBackStack() },
                        onSaved = { savedId, isNew ->
                            nav.popBackStack()
                            if (isNew) scope.launch { snackbar.showSnackbar("Mission enregistrée. Bullylist veille.") }
                            else if (nav.currentDestination?.route != "task/{id}") nav.navigate("task/$savedId")
                        },
                    )
                }
                composable("project/{id}", arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                    val pid = e.arguments?.getLong("id") ?: 0L
                    var editing by remember { mutableStateOf(false) }
                    val projects by remember(pid) { container.projects.observe(pid) }.collectAsStateWithLifecycle(initialValue = null)
                    TasksScreen(
                        container, snackbar, projectId = pid,
                        onOpenTask = { nav.navigate("task/$it") },
                        onOpenEditor = { nav.navigate(editRoute(project = pid, text = it)) },
                        onBack = { nav.popBackStack() },
                        onEditProject = { editing = true },
                    )
                    val project = projects
                    if (editing && project != null) {
                        ProjectDialog(project, onDismiss = { editing = false },
                            onSave = { n, c -> scope.launch { container.projects.update(project.copy(name = n, color = c)) }; editing = false },
                            onDelete = { scope.launch { container.taskManager.deleteProject(pid) }; editing = false; nav.popBackStack() })
                    }
                }
                composable("history") { HistoryScreen(container, onBack = { nav.popBackStack() }, onOpenTask = { nav.navigate("task/$it") }) }
                composable("alarms") { AlarmSettingsScreen(container, onBack = { nav.popBackStack() }) }
                composable("punchlines") { PunchlinesScreen(container, onBack = { nav.popBackStack() }) }
                composable("permissions") { PermissionsScreen(container, onBack = { nav.popBackStack() }) }
            }
        }
        InAppAlertBanner(onOpen = { nav.navigate("task/$it") })
    }
}

private fun editRoute(id: Long = 0, project: Long = 0, text: String? = null): String =
    "edit?id=$id&project=$project" + (if (!text.isNullOrBlank()) "&text=${Uri.encode(text)}" else "")

@Composable
private fun BottomBar(nav: NavHostController, route: String?) {
    NavigationBar(containerColor = Brr.colors.card, tonalElevation = 0.dp) {
        Tab.entries.forEach { tab ->
            NavigationBarItem(
                selected = route == tab.route,
                onClick = {
                    nav.navigate(tab.route) {
                        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(tab.icon, tab.label) },
                label = { Text(tab.label) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary, selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
                    unselectedIconColor = Brr.colors.muted, unselectedTextColor = Brr.colors.muted,
                ),
            )
        }
    }
}

/** Bandeau secoué affiché quand un rappel (niveau 2+) tombe pendant que l'appli est ouverte. */
@Composable
private fun InAppAlertBanner(onOpen: (Long) -> Unit) {
    var alert by remember { mutableStateOf<InAppAlert?>(null) }
    LaunchedEffect(Unit) { InAppAlerts.alerts.collect { alert = it } }
    LaunchedEffect(alert) { if (alert != null) { delay(7000); alert = null } }
    val wobble = remember { Animatable(0f) }
    LaunchedEffect(alert) {
        if (alert != null) repeat(6) { i -> wobble.animateTo(if (i % 2 == 0) 6f else -6f, tween(70)) }
        wobble.animateTo(0f, tween(70))
    }
    AnimatedVisibility(alert != null, enter = slideInVertically { -it } + fadeIn(), exit = slideOutVertically { -it } + fadeOut()) {
        val a = alert ?: return@AnimatedVisibility
        val color = if (a.level == ReminderLevel.INSISTANT) BrrPalette.Orange else BrrPalette.Red
        Box(Modifier.fillMaxWidth().statusBarsPadding().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().graphicsLayer { translationX = wobble.value * density }.clip(RoundedCornerShape(18.dp))
                    .background(color).clickable { alert = null; onOpen(a.taskId) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(BrrIcons.Megaphone, null, tint = Color.White, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(a.title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                    Text(a.message, color = Color.White.copy(alpha = 0.9f), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                }
            }
        }
    }
}
