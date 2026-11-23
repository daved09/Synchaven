package de.dave.synchaven.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.dave.synchaven.R
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarInput
import de.dave.synchaven.ui.common.ColorSwatches
import de.dave.synchaven.ui.event.ReminderEditor
import de.dave.synchaven.util.Reminders
import de.dave.synchaven.ui.theme.CALENDAR_COLORS

/** Kalender anlegen ([calendar] = null) oder bearbeiten */
@Composable
fun CalendarDialog(calendar: Calendar?, onSave: (CalendarInput) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(calendar?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(calendar?.description.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(calendar?.color ?: CALENDAR_COLORS[1]) }
    var defaults by rememberSaveable { mutableStateOf(calendar?.defaultReminders ?: Reminders.DEFAULT_TIMED) }
    var allDayDefaults by rememberSaveable { mutableStateOf(calendar?.defaultAllDayReminders ?: Reminders.DEFAULT_ALL_DAY) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (calendar == null) R.string.calendar_new else R.string.calendar_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, label = { Text(stringResource(R.string.label_name)) },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = description, onValueChange = { description = it }, label = { Text(stringResource(R.string.label_description)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(stringResource(R.string.label_color), style = MaterialTheme.typography.labelLarge)
                ColorSwatches(CALENDAR_COLORS, color, { color = it })

                Text(stringResource(R.string.calendar_default_reminders), style = MaterialTheme.typography.labelLarge)
                Text(
                    stringResource(R.string.calendar_default_reminders_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (calendar?.isBirthdays != true) {
                    Text(stringResource(R.string.reminders_timed), style = MaterialTheme.typography.bodyMedium)
                    ReminderEditor(defaults, allDay = false, onChange = { defaults = it })
                    Text(stringResource(R.string.reminders_all_day), style = MaterialTheme.typography.bodyMedium)
                }
                ReminderEditor(allDayDefaults, allDay = true, onChange = { allDayDefaults = it })
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(CalendarInput(name.trim(), color, description.trim(), calendar?.visible ?: true, defaults, allDayDefaults))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
