package de.dave.synchaven.ui.calendar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CalendarViewDay
import androidx.compose.material.icons.outlined.CalendarViewMonth
import androidx.compose.material.icons.outlined.CalendarViewWeek
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.dave.synchaven.R
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.store.ViewMode
import de.dave.synchaven.ui.theme.parseHexColor

val ViewMode.icon: ImageVector
    get() = when (this) {
        ViewMode.MONTH -> Icons.Outlined.CalendarViewMonth
        ViewMode.WEEK -> Icons.Outlined.CalendarViewWeek
        ViewMode.DAY -> Icons.Outlined.CalendarViewDay
        ViewMode.LIST -> Icons.Outlined.ViewAgenda
    }

/** Aktionen für einen Kalender aus dem Menü der Seitenleiste */
enum class CalendarAction { EDIT, EXPORT, IMPORT, DELETE }

/** Aktionen für eine Aufgabenliste aus dem Menü der Seitenleiste */
enum class TaskListAction { EDIT, DELETE }

/** Seitenleiste: Ansicht wählen, Kalender und Aufgabenlisten ein-/ausblenden und verwalten. */
@Composable
fun CalendarDrawer(
    mode: ViewMode,
    calendars: List<Calendar>,
    onSelectMode: (ViewMode) -> Unit,
    onToggle: (Calendar) -> Unit,
    onAction: (Calendar, CalendarAction) -> Unit,
    onNewCalendar: () -> Unit,
    taskLists: List<TaskList>,
    onOpenTasks: () -> Unit,
    onToggleTaskList: (TaskList) -> Unit,
    onTaskListAction: (TaskList, TaskListAction) -> Unit,
    onNewTaskList: () -> Unit,
) {
    ModalDrawerSheet {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 12.dp)) {
            Text(
                stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 20.dp),
            )
            for (m in ViewMode.entries) {
                NavigationDrawerItem(
                    label = { Text(stringResource(m.labelRes)) },
                    icon = { Icon(m.icon, null) },
                    selected = m == mode,
                    onClick = { onSelectMode(m) },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                stringResource(R.string.calendar_my_calendars), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            for (cal in calendars) CalendarItem(cal, onToggle, onAction)
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.calendar_new)) },
                icon = { Icon(Icons.Outlined.Add, null) },
                selected = false,
                onClick = onNewCalendar,
            )
            HorizontalDivider(Modifier.padding(vertical = 12.dp))
            Text(
                stringResource(R.string.task_lists_header), style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.tasks_show_all)) },
                icon = { Icon(Icons.Outlined.Checklist, null) },
                selected = false,
                onClick = onOpenTasks,
            )
            for (list in taskLists) {
                TaskListItem(list, canDelete = taskLists.size > 1, onToggleTaskList, onTaskListAction)
            }
            NavigationDrawerItem(
                label = { Text(stringResource(R.string.task_list_new)) },
                icon = { Icon(Icons.Outlined.Add, null) },
                selected = false,
                onClick = onNewTaskList,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun CalendarItem(cal: Calendar, onToggle: (Calendar) -> Unit, onAction: (Calendar, CalendarAction) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    val color = parseHexColor(cal.color)
    NavigationDrawerItem(
        label = { Text(cal.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = {
            Checkbox(
                checked = cal.visible, onCheckedChange = { onToggle(cal) },
                colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color),
            )
        },
        badge = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.calendar_menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(stringResource(R.string.action_edit), Icons.Outlined.Edit) { menu = false; onAction(cal, CalendarAction.EDIT) }
                    MenuItem(stringResource(R.string.calendar_export), Icons.Outlined.Download) { menu = false; onAction(cal, CalendarAction.EXPORT) }
                    if (!cal.isBirthdays) {
                        MenuItem(stringResource(R.string.calendar_import), Icons.Outlined.Upload) { menu = false; onAction(cal, CalendarAction.IMPORT) }
                        MenuItem(stringResource(R.string.action_delete), Icons.Outlined.Delete) { menu = false; onAction(cal, CalendarAction.DELETE) }
                    }
                }
            }
        },
        selected = false,
        onClick = { onToggle(cal) },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Aufgabenliste: Häkchen blendet ihre Aufgaben im Kalender ein/aus; die letzte Liste ist nicht löschbar. */
@Composable
private fun TaskListItem(
    list: TaskList,
    canDelete: Boolean,
    onToggle: (TaskList) -> Unit,
    onAction: (TaskList, TaskListAction) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val color = parseHexColor(list.color)
    NavigationDrawerItem(
        label = { Text(list.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = {
            Checkbox(
                checked = list.visible, onCheckedChange = { onToggle(list) },
                colors = CheckboxDefaults.colors(checkedColor = color, uncheckedColor = color),
            )
        },
        badge = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, stringResource(R.string.task_list_menu)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    MenuItem(stringResource(R.string.action_edit), Icons.Outlined.Edit) { menu = false; onAction(list, TaskListAction.EDIT) }
                    if (canDelete) {
                        MenuItem(stringResource(R.string.action_delete), Icons.Outlined.Delete) { menu = false; onAction(list, TaskListAction.DELETE) }
                    }
                }
            }
        },
        selected = false,
        onClick = { onToggle(list) },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun MenuItem(text: String, icon: ImageVector, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(text) }, leadingIcon = { Icon(icon, null) }, onClick = onClick)
}
