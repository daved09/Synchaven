package de.dave.synchaven.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.dave.synchaven.R
import de.dave.synchaven.ui.common.TimePickerModal
import de.dave.synchaven.util.FMT_TIME
import de.dave.synchaven.util.Reminders
import java.time.LocalTime

/** Liste von Erinnerungen (Minuten vor Beginn) mit Entfernen-Knöpfen und einem Menü zum Hinzufügen. */
@Composable
fun ReminderEditor(reminders: List<Int>, allDay: Boolean, onChange: (List<Int>) -> Unit, modifier: Modifier = Modifier) {
    val res = LocalContext.current.resources
    var menu by remember { mutableStateOf(false) }
    var custom by rememberSaveable { mutableStateOf(false) }

    Column(modifier) {
        for (m in reminders) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(Reminders.describe(res, m, allDay), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { onChange(reminders - m) }) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.reminder_remove))
                }
            }
        }
        if (reminders.size < Reminders.MAX_COUNT) {
            Row {
                TextButton(onClick = { menu = true }) {
                    Icon(Icons.Outlined.Add, null)
                    Text(stringResource(R.string.reminder_add), Modifier.padding(start = 8.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    val presets = if (allDay) Reminders.ALL_DAY_PRESETS else Reminders.TIMED_PRESETS
                    for (p in presets.filterNot { it in reminders }) {
                        DropdownMenuItem(
                            text = { Text(Reminders.describe(res, p, allDay)) },
                            onClick = { menu = false; onChange(Reminders.sorted(reminders + p)) },
                        )
                    }
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reminder_custom)) },
                        leadingIcon = { Icon(Icons.Outlined.Tune, null) },
                        onClick = { menu = false; custom = true },
                    )
                }
            }
        }
    }

    if (custom) {
        CustomReminderDialog(allDay, onAdd = { onChange(Reminders.sorted(reminders + it)) }, onDismiss = { custom = false })
    }
}

@Composable
private fun CustomReminderDialog(allDay: Boolean, onAdd: (Int) -> Unit, onDismiss: () -> Unit) {
    var amount by rememberSaveable { mutableStateOf("1") }
    var unit by rememberSaveable { mutableStateOf(60) }
    var time by remember { mutableStateOf(LocalTime.of(9, 0)) }
    var timePicker by remember { mutableStateOf(false) }

    val n = amount.toIntOrNull()
    val minutes = when {
        n == null -> null
        allDay -> Reminders.allDayMinutes(n, time.hour, time.minute)
        else -> n * unit
    }
    val valid = minutes != null && n!! >= 0 && Reminders.isValid(minutes, allDay)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reminder_custom_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = amount, onValueChange = { v -> amount = v.filter(Char::isDigit).take(5) },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(96.dp),
                    )
                    Text(stringResource(if (allDay) R.string.reminder_custom_days else R.string.reminder_custom_before))
                }
                if (allDay) {
                    OutlinedButton(onClick = { timePicker = true }) {
                        Text(stringResource(R.string.reminder_custom_at, time.format(FMT_TIME)))
                    }
                } else {
                    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((value, label) in listOf(
                            1 to R.string.reminder_unit_minutes, 60 to R.string.reminder_unit_hours,
                            Reminders.DAY to R.string.reminder_unit_days, 7 * Reminders.DAY to R.string.reminder_unit_weeks,
                        )) {
                            FilterChip(selected = unit == value, onClick = { unit = value }, label = { Text(stringResource(label)) })
                        }
                    }
                }
                if (!valid && n != null) {
                    Text(stringResource(R.string.reminder_custom_invalid), color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onAdd(minutes!!); onDismiss() }) { Text(stringResource(R.string.reminder_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
    if (timePicker) TimePickerModal(time, { time = it }) { timePicker = false }
}
