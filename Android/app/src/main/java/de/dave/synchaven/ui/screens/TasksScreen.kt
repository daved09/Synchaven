package de.dave.synchaven.ui.screens

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import de.dave.synchaven.data.model.TASK_SORT_DUE
import de.dave.synchaven.data.model.TASK_SORT_MANUAL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import androidx.compose.material.icons.outlined.SubdirectoryArrowRight
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

/** Tasks of a list: quick entry, open tasks in own order (drag to move) or by due date, completed ones below. */
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
                            s.list?.let { l ->
                                for ((sortBy, label) in listOf(TASK_SORT_MANUAL to R.string.tasks_sort_manual, TASK_SORT_DUE to R.string.tasks_sort_due)) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(label)) },
                                        leadingIcon = {
                                            if (l.sortBy == sortBy) Icon(Icons.Outlined.Check, null) else Spacer(Modifier.size(24.dp))
                                        },
                                        onClick = { menu = false; vm.setSortBy(sortBy) },
                                    )
                                }
                                HorizontalDivider()
                            }
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
                    onMove = vm::move,
                )
            }
        }
    }

    selected?.let { task ->
        TaskDetailsSheet(
            task = task,
            list = s.lists.firstOrNull { it.id == task.listId },
            tasks = s.all,
            onToggle = { vm.setCompleted(task, !task.completed) },
            onEdit = { selectedTaskId = null; editing = task },
            onDelete = { selectedTaskId = null; vm.delete(task) },
            onDismiss = { selectedTaskId = null },
            onOpen = { selectedTaskId = it.id },
            onToggleSubtask = { vm.setCompleted(it, !it.completed) },
            onAddSubtask = { vm.addSubtask(task, it) },
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
            subtaskCount = s.all.count { it.parentId == task.id },
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
    onMove: (Task, String?) -> Unit,
) {
    val list = s.list ?: return
    val color = parseHexColor(list.color)
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val reorder = remember(listState) { TaskReorder(listState, scope) }
    reorder.state = s
    reorder.onMove = onMove
    reorder.onPickUp = { haptic.performHapticFeedback(HapticFeedbackType.LongPress) }
    val drag = reorder.drag
    // After dropping, the previewed order stays until the saved one arrives (no jumping back and forth)
    LaunchedEffect(s, drag?.dropped) {
        if (drag?.dropped == true) {
            if (reorder.items(drag.group).map { it.id } != drag.order) delay(1000)
            reorder.drag = null
        }
    }
    var quick by rememberSaveable { mutableStateOf("") }
    fun submit() {
        if (quick.isNotBlank()) {
            onQuickAdd(quick)
            quick = ""
        }
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState, contentPadding = PaddingValues(bottom = 88.dp)) {
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
        for (t in reorder.preview(TOP)) {
            item(key = t.id) {
                TaskRow(t, color, onToggle, onOpen, Modifier.reorderable(reorder, this, t, TOP, follows = null))
            }
            items(reorder.preview(t.id), key = { it.id }) { sub ->
                TaskRow(sub, color, onToggle, onOpen, Modifier.reorderable(reorder, this, sub, t.id, follows = t), indent = true)
            }
        }
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
            if (s.showCompleted) {
                items(s.completed, key = { "done-" + it.id }) { t ->
                    TaskRow(t, color, onToggle, onOpen, parent = t.parentId?.let { p -> s.all.firstOrNull { it.id == p } })
                }
            }
        }
    }
}

@Composable
private fun TaskRow(
    task: Task,
    color: Color,
    onToggle: (Task) -> Unit,
    onOpen: (Task) -> Unit,
    modifier: Modifier = Modifier,
    /** Subtask under its parent task: indented and slightly smaller */
    indent: Boolean = false,
    /** Parent task, as a hint below the title (for completed subtasks) */
    parent: Task? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clickable { onOpen(task) }
            .padding(start = if (indent) 44.dp else 4.dp, end = 16.dp, top = if (indent) 0.dp else 2.dp, bottom = if (indent) 2.dp else 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        IconButton(onClick = { onToggle(task) }) { TaskCheckIcon(task.completed, color, size = if (indent) 20.dp else 24.dp) }
        Column(Modifier.weight(1f).padding(top = 12.dp)) {
            Text(
                task.title, style = if (indent) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                textDecoration = taskDecoration(task.completed),
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (task.completed) 0.6f else 1f),
            )
            parent?.let { p ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Outlined.SubdirectoryArrowRight, null, Modifier.size(14.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        p.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
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

/** Level of the top-level tasks; subtasks are grouped by their parent task's ID */
private const val TOP = ""

/** Ongoing drag: [order] is the previewed order of the dragged task's level */
private class TaskDrag(val task: Task, val group: String, order: List<String>, val startOffset: Int) {
    var order by mutableStateOf(order)
    /** Distance dragged so far (plus the list's own scrolling) */
    var delta by mutableFloatStateOf(0f)
    var moved = false
    /** Let go; the list shows [order] until the saved order arrives */
    var dropped by mutableStateOf(false)
}

/**
 * Own order in the task list: hold a task, then drag it among the tasks of its level (top-level tasks with their
 * subtasks, or the subtasks of one task). The others slide aside; TalkBack offers "Move up/down" instead.
 */
private class TaskReorder(private val listState: LazyListState, private val scope: CoroutineScope) {
    var state = TasksUiState()
    var onMove: (Task, String?) -> Unit = { _, _ -> }
    var onPickUp: () -> Unit = {}
    var drag by mutableStateOf<TaskDrag?>(null)

    private val manual get() = state.list?.sortBy == TASK_SORT_MANUAL

    fun items(group: String): List<Task> = if (group == TOP) state.open else state.subtasks[group].orEmpty()

    /** The level's tasks, in the previewed order while one of them is dragged */
    fun preview(group: String): List<Task> {
        val all = items(group)
        val d = drag?.takeIf { it.group == group } ?: return all
        val byId = all.associateBy { it.id }
        return d.order.mapNotNull { byId[it] } + all.filter { it.id !in d.order }
    }

    /** Subtasks can always be moved, top-level tasks in own order. Open subtasks listed at the top level because their
     *  parent task is completed stay where they are. */
    fun movable(t: Task, group: String) = if (group == TOP) manual && t.parentId == null else t.parentId == group

    /** Lazy items of a task's block: a top-level task with its subtasks */
    private fun members(id: String, group: String) =
        if (group == TOP) listOf(id) + state.subtasks[id].orEmpty().map { it.id } else listOf(id)

    private fun extent(ids: List<String>): Pair<Int, Int>? {
        val infos = listState.layoutInfo.visibleItemsInfo.filter { it.key in ids }
        if (infos.isEmpty()) return null
        return infos.minOf { it.offset } to infos.maxOf { it.offset + it.size }
    }

    private fun offsetOf(id: String) = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == id }?.offset

    /** How far the dragged task (and its subtasks) is shifted from its place in the layout */
    fun shift(d: TaskDrag): Float = offsetOf(d.task.id)?.let { d.startOffset + d.delta - it } ?: 0f

    fun start(t: Task, group: String) {
        val offset = offsetOf(t.id) ?: return
        val current = items(group)
        drag = TaskDrag(current.firstOrNull { it.id == t.id } ?: t, group, current.map { it.id }, offset)
        onPickUp()
    }

    fun dragBy(dy: Float) {
        val d = drag?.takeIf { !it.dropped } ?: return
        d.delta += dy
        val own = extent(members(d.task.id, d.group)) ?: return
        val center = d.startOffset + d.delta + (own.second - own.first) / 2f
        // Past the middle of the neighbour below or above: swap places
        val order = d.order.toMutableList()
        var i = order.indexOf(d.task.id)
        while (i + 1 < order.size) {
            val e = extent(members(order[i + 1], d.group)) ?: break
            if (center <= (e.first + e.second) / 2f) break
            order[i] = order[i + 1].also { order[i + 1] = order[i] }
            i++
        }
        while (i > 0) {
            val e = extent(members(order[i - 1], d.group)) ?: break
            if (center >= (e.first + e.second) / 2f) break
            order[i] = order[i - 1].also { order[i - 1] = order[i] }
            i--
        }
        if (order != d.order) {
            d.order = order
            d.moved = true
        }
        // Scroll along near the edges; the dragged task stays under the finger
        val info = listState.layoutInfo
        val top = d.startOffset + d.delta
        val step = when {
            top < info.viewportStartOffset + EDGE -> -SCROLL_STEP
            top + (own.second - own.first) > info.viewportEndOffset - EDGE -> SCROLL_STEP
            else -> 0f
        }
        // (scrolling moves the layout, which the shift makes up for)
        if (step != 0f) listState.dispatchRawDelta(step)
    }

    fun end(cancel: Boolean) {
        val d = drag ?: return
        if (cancel || !d.moved) {
            drag = null
            return
        }
        d.dropped = true
        // Slide into the new place, then save
        scope.launch {
            val target = offsetOf(d.task.id)?.let { (it - d.startOffset).toFloat() } ?: d.delta
            animate(d.delta, target, animationSpec = tween(150)) { v, _ -> d.delta = v }
        }
        val byId = items(d.group).associateBy { it.id }
        place(d.task, d.order.mapNotNull { byId[it] })
    }

    /** Saves the place: behind the nearest real sibling above it (open subtasks of completed tasks don't count) */
    private fun place(t: Task, order: List<Task>) {
        val i = order.indexOfFirst { it.id == t.id }
        onMove(t, order.take(i).lastOrNull { it.parentId == t.parentId }?.id)
    }

    /** One place up or down (accessibility actions) */
    fun moveBy(t: Task, group: String, step: Int) {
        val order = items(group).toMutableList()
        val i = order.indexOfFirst { it.id == t.id }
        val j = i + step
        if (i < 0 || j !in order.indices) return
        order.add(j, order.removeAt(i))
        place(t, order)
    }

    private companion object {
        const val EDGE = 48f
        const val SCROLL_STEP = 12f
    }
}

/**
 * Gestures, shift and animation of a row. [follows]: the parent task of a subtask – it moves along while its
 * parent task is dragged.
 */
@Composable
private fun Modifier.reorderable(reorder: TaskReorder, scope: LazyItemScope, t: Task, group: String, follows: Task?): Modifier {
    val d = reorder.drag
    val carried = d != null && !d.dropped && (d.task.id == t.id || d.task.id == follows?.id)
    val lifted = d != null && !d.dropped && d.task.id == t.id
    val canMove = reorder.movable(t, group)
    val up = stringResource(R.string.tasks_move_up)
    val down = stringResource(R.string.tasks_move_down)
    val elevation by animateDpAsState(if (lifted) 6.dp else 0.dp, label = "lift")
    var m = this.then(
        if (carried) {
            Modifier.zIndex(1f).graphicsLayer { translationY = reorder.drag?.let(reorder::shift) ?: 0f }
        } else {
            with(scope) { Modifier.animateItem() }
        },
    )
    if (elevation > 0.dp) {
        val shape = RoundedCornerShape(12.dp)
        m = m.shadow(elevation, shape).background(MaterialTheme.colorScheme.surfaceContainerHighest, shape)
    }
    if (!canMove) return m
    return m
        .pointerInput(t.id, group) {
            detectDragGesturesAfterLongPress(
                onDragStart = { if (reorder.movable(t, group)) reorder.start(t, group) },
                onDrag = { change, amount ->
                    if (reorder.drag != null) {
                        change.consume()
                        reorder.dragBy(amount.y)
                    }
                },
                onDragEnd = { reorder.end(cancel = false) },
                onDragCancel = { reorder.end(cancel = true) },
            )
        }
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction(up) { reorder.moveBy(t, group, -1); true },
                CustomAccessibilityAction(down) { reorder.moveBy(t, group, 1); true },
            )
        }
}
