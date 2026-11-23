package de.dave.synchaven.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.dave.synchaven.R
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.ui.calendar.ListDot
import de.dave.synchaven.ui.calendar.TaskCheckIcon
import de.dave.synchaven.ui.calendar.TaskDetailsSheet
import de.dave.synchaven.ui.calendar.TaskEditDialog
import de.dave.synchaven.ui.calendar.TaskListDialog
import androidx.compose.material.icons.automirrored.outlined.PlaylistAdd
import de.dave.synchaven.ui.calendar.taskDecoration
import de.dave.synchaven.ui.common.CollectMessages
import de.dave.synchaven.ui.common.ConfirmDialog
import de.dave.synchaven.ui.common.ErrorBox
import de.dave.synchaven.ui.common.LoadingBox
import de.dave.synchaven.ui.common.appViewModel
import de.dave.synchaven.ui.theme.parseHexColor
import de.dave.synchaven.ui.viewmodels.TasksUiState
import de.dave.synchaven.ui.viewmodels.TasksViewModel
import de.dave.synchaven.util.dueText
import de.dave.synchaven.util.isOverdue
import de.dave.synchaven.ui.common.MainTab
import de.dave.synchaven.ui.common.MainBottomBar
import androidx.compose.material.icons.outlined.Settings

/** Aufgaben einer Liste: Schnelleingabe, offene Aufgaben nach Fälligkeit, darunter die erledigten. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TasksScreen(onSelectTab: (MainTab) -> Unit, onOpenSettings: () -> Unit) {
    val vm = appViewModel { TasksViewModel(it.taskRepository) }
    val s by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    CollectMessages(vm.messages.flow, snackbar)

    var selectedTaskId by rememberSaveable { mutableStateOf<String?>(null) }
    val selected = selectedTaskId?.let { id -> s.all.firstOrNull { it.id == id } }
    var editing by remember { mutableStateOf<Task?>(null) }
    var creating by remember { mutableStateOf(false) }
    var creatingList by remember { mutableStateOf(false) }
    var askClear by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tasks_title)) },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.action_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.task_list_new)) },
                                leadingIcon = { Icon(Icons.AutoMirrored.Outlined.PlaylistAdd, null) },
                                onClick = { menu = false; creatingList = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tasks_clear_completed)) },
                                leadingIcon = { Icon(Icons.Outlined.DeleteSweep, null) },
                                enabled = s.completed.isNotEmpty(),
                                onClick = { menu = false; askClear = true },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.action_refresh)) },
                                leadingIcon = { Icon(Icons.Outlined.Refresh, null) },
                                onClick = { menu = false; vm.refresh() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.tab_settings)) },
                                leadingIcon = { Icon(Icons.Outlined.Settings, null) },
                                onClick = { menu = false; onOpenSettings() },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = { MainBottomBar(MainTab.TASKS, onSelectTab) },
        floatingActionButton = {
            if (s.list != null) {
                FloatingActionButton(onClick = { creating = true }) { Icon(Icons.Outlined.Add, stringResource(R.string.task_new)) }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (s.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                s.error != null && s.lists.isEmpty() -> ErrorBox(s.error!!, onRetry = vm::refresh)
                s.list == null -> if (s.loading || s.notLoaded) LoadingBox() else CenteredText(stringResource(R.string.tasks_no_lists))
                else -> TaskListContent(
                    s = s,
                    onSelectList = vm::selectList,
                    onQuickAdd = vm::quickAdd,
                    onToggle = { vm.setCompleted(it, !it.completed) },
                    onOpen = { selectedTaskId = it.id },
                    onToggleCompleted = vm::toggleShowCompleted,
                )
            }
        }
    }

    selected?.let { task ->
        TaskDetailsSheet(
            task = task,
            list = s.lists.firstOrNull { it.id == task.listId },
            onToggle = { vm.setCompleted(task, !task.completed) },
            onEdit = { selectedTaskId = null; editing = task },
            onDelete = { selectedTaskId = null; vm.delete(task) },
            onDismiss = { selectedTaskId = null },
        )
    }
    if (creatingList) {
        TaskListDialog(null, onSave = vm::createList, onDismiss = { creatingList = false })
    }
    if (creating) {
        TaskEditDialog(
            task = null, lists = s.lists, defaultListId = s.list?.id, defaultDate = null,
            onSave = { vm.save(null, it) }, onDismiss = { creating = false },
        )
    }
    editing?.let { task ->
        TaskEditDialog(
            task = task, lists = s.lists, defaultListId = task.listId, defaultDate = null,
            onSave = { vm.save(task.id, it) }, onDismiss = { editing = null },
        )
    }
    if (askClear) {
        ConfirmDialog(
            title = stringResource(R.string.tasks_clear_completed),
            text = pluralStringResource(R.plurals.msg_tasks_clear_confirm, s.completed.size, s.completed.size),
            onConfirm = vm::clearCompleted,
            onDismiss = { askClear = false },
        )
    }
}

@Composable
private fun TaskListContent(
    s: TasksUiState,
    onSelectList: (String) -> Unit,
    onQuickAdd: (String) -> Unit,
    onToggle: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    onToggleCompleted: () -> Unit,
) {
    val list = s.list ?: return
    val color = parseHexColor(list.color)
    var quick by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (quick.isNotBlank()) {
            onQuickAdd(quick)
            quick = ""
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 88.dp)) {
        if (s.lists.size > 1) {
            item(key = "lists") {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(s.lists, key = { it.id }) { l ->
                        FilterChip(
                            selected = l.id == list.id,
                            onClick = { onSelectList(l.id) },
                            label = { Text(if (l.openCount > 0) "${l.name} (${l.openCount})" else l.name) },
                            leadingIcon = { ListDot(l.color) },
                        )
                    }
                }
            }
        }
        item(key = "quick") {
            OutlinedTextField(
                value = quick,
                onValueChange = { if (it.length <= 500) quick = it },
                placeholder = { Text(stringResource(R.string.tasks_add_hint)) },
                leadingIcon = { Icon(Icons.Outlined.Add, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (s.open.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(Icons.Outlined.DoneAll, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.tasks_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        items(s.open, key = { it.id }) { t -> TaskRow(t, color, onToggle, onOpen) }
        if (s.completed.isNotEmpty()) {
            item(key = "done-head") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onToggleCompleted)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (s.showCompleted) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        stringResource(R.string.tasks_completed_section, s.completed.size),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (s.showCompleted) items(s.completed, key = { "done-" + it.id }) { t -> TaskRow(t, color, onToggle, onOpen) }
        }
    }
}

@Composable
private fun TaskRow(task: Task, color: Color, onToggle: (Task) -> Unit, onOpen: (Task) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onOpen(task) }
            .padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconButton(onClick = { onToggle(task) }) { TaskCheckIcon(task.completed, color) }
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(
                task.title, style = MaterialTheme.typography.bodyLarge,
                textDecoration = taskDecoration(task.completed),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (task.completed) 0.6f else 1f),
            )
            if (task.notes.isNotBlank() && !task.completed) {
                Text(
                    task.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            task.dueText()?.let { due ->
                if (task.completed) {
                    Text(due.asString(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    val overdue = task.isOverdue()
                    AssistChip(
                        onClick = { onOpen(task) },
                        label = { Text(due.asString(), style = MaterialTheme.typography.labelSmall) },
                        leadingIcon = { Icon(Icons.Outlined.Event, null, Modifier.size(14.dp)) },
                        colors = if (overdue) AssistChipDefaults.assistChipColors(
                            labelColor = MaterialTheme.colorScheme.error,
                            leadingIconContentColor = MaterialTheme.colorScheme.error,
                        ) else AssistChipDefaults.assistChipColors(),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun CenteredText(text: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
