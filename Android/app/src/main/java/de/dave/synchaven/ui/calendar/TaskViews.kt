package de.dave.synchaven.ui.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import de.dave.synchaven.R
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.model.TaskListInput
import de.dave.synchaven.ui.common.ColorSwatches
import de.dave.synchaven.ui.common.ConfirmDialog
import de.dave.synchaven.ui.common.DatePickerModal
import de.dave.synchaven.ui.common.TimePickerModal
import de.dave.synchaven.ui.theme.CALENDAR_COLORS
import de.dave.synchaven.ui.theme.parseHexColor
import de.dave.synchaven.util.FMT_DATE_TIME_LONG
import de.dave.synchaven.util.FMT_TASK_DAY_YEAR
import de.dave.synchaven.util.FMT_TIME
import de.dave.synchaven.util.FMT_WEEKDAY_DATE
import de.dave.synchaven.util.dueLocal
import de.dave.synchaven.util.parseInstant
import de.dave.synchaven.util.taskDue
import de.dave.synchaven.util.toLocal
import java.time.LocalDate
import java.time.LocalTime

/** Symbol einer Aufgabe: leerer Kreis oder Haken */
@Composable
fun TaskCheckIcon(completed: Boolean, tint: Color, modifier: Modifier = Modifier, size: Dp = 24.dp) {
    Icon(
        if (completed) Icons.Filled.CheckCircle else Icons.Outlined.RadioButtonUnchecked,
        stringResource(if (completed) R.string.task_mark_open else R.string.task_mark_done),
        modifier.size(size),
        tint = tint,
    )
}

/** Textdekoration erledigter Aufgaben */
fun taskDecoration(completed: Boolean): TextDecoration? = if (completed) TextDecoration.LineThrough else null

/** Lange Fälligkeit für die Details: „Montag, 24. November 2026 · 09:30“ */
@Composable
private fun longDue(task: Task): String {
    val d = task.dueLocal() ?: return stringResource(R.string.task_no_date)
    val day = d.toLocalDate().format(FMT_WEEKDAY_DATE)
    return if (task.allDay) day else stringResource(R.string.task_due_long_time, day, d.format(FMT_TIME))
}

/** Details einer Aufgabe mit Abhaken, Bearbeiten und Löschen */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskDetailsSheet(
    task: Task,
    list: TaskList?,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var askDelete by remember { mutableStateOf(false) }
    val color = parseHexColor(list?.color ?: "#1A73E8")
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                TaskCheckIcon(task.completed, color, Modifier.padding(top = 4.dp))
                Spacer(Modifier.size(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        task.title, style = MaterialTheme.typography.headlineSmall,
                        textDecoration = taskDecoration(task.completed),
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (task.completed) 0.7f else 1f),
                    )
                    Text(longDue(task), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val at = task.completedAt?.takeIf { task.completed }?.let { runCatching { parseInstant(it).toLocal() }.getOrNull() }
                    if (at != null) {
                        Text(
                            stringResource(R.string.task_completed_at, at.format(FMT_DATE_TIME_LONG)),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (task.notes.isNotBlank()) DetailLine(Icons.AutoMirrored.Outlined.Notes, task.notes)
            list?.let { DetailLine(Icons.Outlined.Checklist, it.name) }

            Button(onClick = onToggle, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Icon(if (task.completed) Icons.AutoMirrored.Outlined.Undo else Icons.Outlined.Check, null, Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(if (task.completed) R.string.task_mark_open else R.string.task_mark_done))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_edit))
                }
                OutlinedButton(onClick = { askDelete = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Delete, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.action_delete))
                }
            }
        }
    }
    if (askDelete) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_task_title),
            text = stringResource(R.string.dialog_delete_task_text, task.title),
            onConfirm = onDelete,
            onDismiss = { askDelete = false },
        )
    }
}

@Composable
private fun DetailLine(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Aufgabe anlegen ([task] = null) oder bearbeiten. Neue Aufgaben beginnen in [defaultListId] und – falls gesetzt –
 * ganztägig am [defaultDate]. Der Status (erledigt/offen) bleibt beim Bearbeiten unverändert.
 */
@Composable
fun TaskEditDialog(
    task: Task?,
    lists: List<TaskList>,
    defaultListId: String?,
    defaultDate: LocalDate?,
    onSave: (TaskInput) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialDue = task?.dueLocal()
    var title by rememberSaveable { mutableStateOf(task?.title.orEmpty()) }
    var notes by rememberSaveable { mutableStateOf(task?.notes.orEmpty()) }
    var date by rememberSaveable { mutableStateOf(if (task == null) defaultDate else initialDue?.toLocalDate()) }
    var time by rememberSaveable { mutableStateOf(if (task != null && !task.allDay) initialDue?.toLocalTime() else null) }
    var listId by rememberSaveable { mutableStateOf(task?.listId ?: defaultListId ?: lists.firstOrNull()?.id.orEmpty()) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTime by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (task == null) R.string.task_new else R.string.task_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = title, onValueChange = { if (it.length <= 500) title = it },
                    label = { Text(stringResource(R.string.task_title)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = notes, onValueChange = { notes = it }, label = { Text(stringResource(R.string.task_notes)) },
                    minLines = 2, modifier = Modifier.fillMaxWidth(),
                )
                // Fälligkeit: Datum und optional Uhrzeit
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = { pickDate = true },
                        label = { Text(date?.format(FMT_TASK_DAY_YEAR) ?: stringResource(R.string.task_add_date)) },
                        leadingIcon = { Icon(Icons.Outlined.Event, null, Modifier.size(18.dp)) },
                    )
                    if (date != null) {
                        IconButton(onClick = { date = null; time = null }) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.task_remove_date))
                        }
                    }
                }
                if (date != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AssistChip(
                            onClick = { pickTime = true },
                            label = { Text(time?.format(FMT_TIME) ?: stringResource(R.string.task_add_time)) },
                            leadingIcon = { Icon(Icons.Outlined.Schedule, null, Modifier.size(18.dp)) },
                        )
                        if (time != null) {
                            IconButton(onClick = { time = null }) {
                                Icon(Icons.Outlined.Close, stringResource(R.string.task_remove_time))
                            }
                        }
                    }
                }
                if (lists.size > 1) {
                    val selected = lists.firstOrNull { it.id == listId }
                    Box {
                        AssistChip(
                            onClick = { listMenu = true },
                            label = { Text(selected?.name ?: stringResource(R.string.tasks_select_list)) },
                            leadingIcon = { ListDot(selected?.color) },
                        )
                        DropdownMenu(expanded = listMenu, onDismissRequest = { listMenu = false }) {
                            for (l in lists) {
                                DropdownMenuItem(
                                    text = { Text(l.name) },
                                    leadingIcon = { ListDot(l.color) },
                                    onClick = { listId = l.id; listMenu = false },
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && listId.isNotEmpty(),
                onClick = {
                    onSave(TaskInput(listId, title.trim(), notes.trimEnd(), taskDue(date, time), allDay = time == null))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (pickDate) DatePickerModal(date ?: LocalDate.now(), onPick = { date = it }, onDismiss = { pickDate = false })
    if (pickTime) {
        TimePickerModal(time ?: LocalTime.of(9, 0), onPick = { time = it }, onDismiss = { pickTime = false })
    }
}

/** Farbpunkt einer Aufgabenliste */
@Composable
fun ListDot(color: String?, size: Dp = 12.dp) {
    Box(Modifier.size(size).background(parseHexColor(color ?: "#1A73E8"), CircleShape))
}

/** Aufgabenliste anlegen ([list] = null) oder umbenennen/umfärben */
@Composable
fun TaskListDialog(list: TaskList?, onSave: (TaskListInput) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(list?.name.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(list?.color ?: CALENDAR_COLORS[0]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (list == null) R.string.task_list_new else R.string.task_list_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.label_color), style = MaterialTheme.typography.labelLarge)
                ColorSwatches(CALENDAR_COLORS, color, { color = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(TaskListInput(name.trim(), color, list?.visible ?: true)); onDismiss() },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
