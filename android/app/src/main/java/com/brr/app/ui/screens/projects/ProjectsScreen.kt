package com.brr.app.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.brr.app.AppContainer
import com.brr.app.data.db.ProjectEntity
import com.brr.app.data.db.ProjectWithCounts
import com.brr.app.data.repository.ProjectRepository
import com.brr.app.ui.components.BrrCard
import com.brr.app.ui.components.BrrIcons
import com.brr.app.ui.components.EmptyState
import com.brr.app.ui.components.brrViewModel
import com.brr.app.ui.theme.Brr
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ProjectsViewModel(private val c: AppContainer) : ViewModel() {
    val projects: StateFlow<List<ProjectWithCounts>?> = c.projects.observeWithCounts().map { it as List<ProjectWithCounts>? }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun save(existing: ProjectEntity?, name: String, color: Long) = viewModelScope.launch {
        if (name.isBlank()) return@launch
        if (existing == null) c.projects.create(name, color) else c.projects.update(existing.copy(name = name, color = color))
    }

    fun delete(p: ProjectEntity) = viewModelScope.launch { c.taskManager.deleteProject(p.id) }
}

@Composable
fun ProjectsScreen(container: AppContainer, onOpenProject: (Long) -> Unit) {
    val vm = brrViewModel { ProjectsViewModel(container) }
    val list by vm.projects.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<ProjectEntity?>(null) }
    var creating by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 120.dp)) {
            item {
                Column(Modifier.statusBarsPadding().padding(top = 20.dp, bottom = 16.dp)) {
                    Text("Projets", style = MaterialTheme.typography.displaySmall)
                    Text("Range tes missions par thème.", style = MaterialTheme.typography.bodyMedium, color = Brr.colors.muted)
                }
            }
            val l = list
            if (l != null && l.isEmpty()) item {
                EmptyState("Aucun projet", "Crée-en un avec +, ou tape #NomDuProjet dans la saisie rapide.", BrrIcons.Folder)
            }
            items(l ?: emptyList(), key = { it.project.id }) { p ->
                val total = p.openCount + p.doneCount
                BrrCard(Modifier.fillMaxWidth().padding(bottom = 10.dp), onClick = { onOpenProject(p.project.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Color(p.project.color).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                            Icon(BrrIcons.Folder, null, tint = Color(p.project.color), modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.project.name, style = MaterialTheme.typography.titleMedium)
                            Text("${p.openCount} en cours · ${p.doneCount} terminée${if (p.doneCount > 1) "s" else ""}",
                                style = MaterialTheme.typography.bodySmall, color = Brr.colors.muted)
                        }
                        TextButton(onClick = { editing = p.project }) { Text("Modifier") }
                    }
                    if (total > 0) {
                        Spacer(Modifier.height(10.dp))
                        LinearProgressIndicator(
                            progress = { p.doneCount.toFloat() / total }, color = Color(p.project.color), trackColor = Brr.colors.cardHigh,
                            modifier = Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(3.dp)),
                        )
                    }
                }
            }
        }
        FloatingActionButton(
            onClick = { creating = true }, containerColor = MaterialTheme.colorScheme.primary, contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp), modifier = Modifier.align(Alignment.BottomEnd).padding(20.dp),
        ) { Icon(Icons.Default.Add, "Nouveau projet") }
    }

    if (creating || editing != null) {
        ProjectDialog(
            existing = editing,
            onDismiss = { creating = false; editing = null },
            onSave = { name, color -> vm.save(editing, name, color); creating = false; editing = null },
            onDelete = editing?.let { e -> { vm.delete(e); editing = null } },
        )
    }
}

@Composable
fun ProjectDialog(existing: ProjectEntity?, onDismiss: () -> Unit, onSave: (String, Long) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var color by remember { mutableStateOf(existing?.color ?: ProjectRepository.PALETTE.first()) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "Nouveau projet" else "Modifier le projet") },
        text = {
            Column {
                OutlinedTextField(name, { name = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProjectRepository.PALETTE.forEach { c ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(Color(c))
                                .border(2.dp, if (c == color) MaterialTheme.colorScheme.onBackground else Color.Transparent, CircleShape)
                                .clickable { color = c },
                            contentAlignment = Alignment.Center,
                        ) { if (c == color) Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(14.dp)) }
                    }
                }
                if (onDelete != null) {
                    Spacer(Modifier.height(16.dp))
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(if (confirmDelete) "Confirmer : les tâches seront conservées sans projet" else "Supprimer le projet", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name, color) }, enabled = name.isNotBlank()) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
