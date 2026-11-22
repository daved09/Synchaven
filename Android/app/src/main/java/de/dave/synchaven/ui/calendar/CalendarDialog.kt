package de.dave.synchaven.ui.calendar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
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
import de.dave.synchaven.ui.theme.CALENDAR_COLORS

/** Kalender anlegen ([calendar] = null) oder bearbeiten */
@Composable
fun CalendarDialog(calendar: Calendar?, onSave: (CalendarInput) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(calendar?.name.orEmpty()) }
    var description by rememberSaveable { mutableStateOf(calendar?.description.orEmpty()) }
    var color by rememberSaveable { mutableStateOf(calendar?.color ?: CALENDAR_COLORS[1]) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (calendar == null) R.string.calendar_new else R.string.calendar_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(CalendarInput(name.trim(), color, description.trim(), calendar?.visible ?: true))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
