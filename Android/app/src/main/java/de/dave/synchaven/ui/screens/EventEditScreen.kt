package de.dave.synchaven.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import de.dave.synchaven.R
import de.dave.synchaven.ui.common.DatePickerModal
import de.dave.synchaven.ui.common.ErrorBox
import de.dave.synchaven.ui.common.LoadingBox
import de.dave.synchaven.ui.common.TimePickerModal
import de.dave.synchaven.ui.common.appViewModel
import de.dave.synchaven.ui.theme.parseHexColor
import de.dave.synchaven.util.FMT_TIME
import de.dave.synchaven.util.deviceZone
import de.dave.synchaven.ui.event.RecurrenceEditor
import de.dave.synchaven.ui.viewmodels.EventEditUiState
import de.dave.synchaven.ui.viewmodels.EventEditViewModel
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val FMT_EDIT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE, d. MMM yyyy", Locale.GERMANY)

/** Welcher Datums-/Zeitwähler gerade offen ist */
private enum class Picker { START_DATE, START_TIME, END_DATE, END_TIME }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventEditScreen(onClose: () -> Unit) {
    val vm = appViewModel { EventEditViewModel(it.calendarRepository, it.settingsStore, createSavedStateHandle()) }
    val s by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    var picker by remember { mutableStateOf<Picker?>(null) }

    LaunchedEffect(s.done) { if (s.done) onClose() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, stringResource(R.string.action_cancel)) } },
                title = { Text(stringResource(if (s.isNew) R.string.calendar_new_event else R.string.event_edit)) },
                actions = {
                    Button(
                        onClick = vm::save,
                        enabled = !s.loading && !s.saving,
                        modifier = Modifier.padding(end = 8.dp),
                    ) {
                        if (s.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text(stringResource(R.string.action_save))
                    }
                },
            )
        },
    ) { padding ->
        when {
            s.loading -> LoadingBox(Modifier.padding(padding))
            s.calendars.isEmpty() && s.error != null -> ErrorBox(s.error!!, onRetry = null, modifier = Modifier.padding(padding))
            else -> Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .wrapContentWidth()
                    .widthIn(max = 720.dp)
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = s.title,
                    onValueChange = vm::onTitleChange,
                    label = { Text(stringResource(R.string.event_title)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth(),
                )

                CalendarPicker(s, vm::onCalendarChange)

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.all_day), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = s.allDay, onCheckedChange = vm::onAllDayChange)
                }

                DateTimeRow(stringResource(R.string.event_start), s.startDate, s.startTime.takeUnless { s.allDay },
                    onDate = { picker = Picker.START_DATE }, onTime = { picker = Picker.START_TIME })
                DateTimeRow(stringResource(R.string.event_end), s.endDate, s.endTime.takeUnless { s.allDay },
                    onDate = { picker = Picker.END_DATE }, onTime = { picker = Picker.END_TIME })

                if (!s.allDay && s.timezone != deviceZone().id) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Public, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.size(6.dp))
                        Text(
                            stringResource(R.string.event_timezone, s.timezone),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalDivider()
                RecurrenceEditor(s.recurrence, s.startDate, settings.weekStart, vm::onRecurrenceChange)
                HorizontalDivider()

                OutlinedTextField(
                    value = s.location,
                    onValueChange = vm::onLocationChange,
                    label = { Text(stringResource(R.string.event_location)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = s.description,
                    onValueChange = vm::onDescriptionChange,
                    label = { Text(stringResource(R.string.label_description)) },
                    minLines = 3,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                s.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    when (picker) {
        Picker.START_DATE -> DatePickerModal(s.startDate, vm::onStartDateChange) { picker = null }
        Picker.END_DATE -> DatePickerModal(s.endDate, vm::onEndDateChange) { picker = null }
        Picker.START_TIME -> TimePickerModal(s.startTime, vm::onStartTimeChange) { picker = null }
        Picker.END_TIME -> TimePickerModal(s.endTime, vm::onEndTimeChange) { picker = null }
        null -> Unit
    }
}

@Composable
private fun DateTimeRow(label: String, date: LocalDate, time: LocalTime?, onDate: () -> Unit, onTime: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(0.6f), style = MaterialTheme.typography.bodyLarge)
        OutlinedButton(onClick = onDate, modifier = Modifier.weight(1.4f)) { Text(date.format(FMT_EDIT_DATE)) }
        if (time != null) {
            OutlinedButton(onClick = onTime, modifier = Modifier.weight(0.8f)) { Text(time.format(FMT_TIME)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CalendarPicker(s: EventEditUiState, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = s.calendars.firstOrNull { it.id == s.calendarId }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = current?.name.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.event_calendar)) },
            leadingIcon = current?.let { { ColorDot(it.color) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (cal in s.calendars) {
                DropdownMenuItem(
                    text = { Text(cal.name) },
                    leadingIcon = { ColorDot(cal.color) },
                    onClick = { expanded = false; onSelect(cal.id) },
                )
            }
        }
    }
}

@Composable
private fun ColorDot(color: String) {
    Box(Modifier.size(14.dp).background(parseHexColor(color), CircleShape))
}
