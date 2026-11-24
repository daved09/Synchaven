package de.dave.synchaven.ui.event

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import de.dave.synchaven.R
import de.dave.synchaven.ui.common.DatePickerModal
import de.dave.synchaven.util.EndMode
import de.dave.synchaven.util.FMT_DATE_SHORT
import de.dave.synchaven.util.Freq
import de.dave.synchaven.util.LOCALE
import de.dave.synchaven.util.MonthlyMode
import de.dave.synchaven.util.RecurrenceForm
import de.dave.synchaven.util.buildRRule
import de.dave.synchaven.util.describeRRule
import de.dave.synchaven.util.ordinalText
import de.dave.synchaven.util.shortName
import de.dave.synchaven.util.weekdayOrdinal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle

/** Recurrence settings (like RecurrenceEditor.vue) */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RecurrenceEditor(
    form: RecurrenceForm,
    start: LocalDate,
    weekStart: DayOfWeek,
    onChange: (RecurrenceForm) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var pickUntil by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = stringResource(form.freq.labelRes),
                onValueChange = {},
                readOnly = true,
                label = { Text(stringResource(R.string.recurrence_label)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                for (f in Freq.entries) {
                    DropdownMenuItem(
                        text = { Text(stringResource(f.labelRes)) },
                        onClick = {
                            expanded = false
                            // When switching to "Custom", use the existing rule as a template
                            val raw = if (f == Freq.CUSTOM && form.raw.isBlank()) buildRRule(form, start).orEmpty() else form.raw
                            onChange(form.copy(freq = f, raw = raw, byDay = form.byDay.ifEmpty { setOf(start.dayOfWeek) }))
                        },
                    )
                }
            }
        }

        if (form.freq == Freq.CUSTOM) {
            OutlinedTextField(
                value = form.raw,
                onValueChange = { onChange(form.copy(raw = it)) },
                label = { Text(stringResource(R.string.recurrence_rrule)) },
                placeholder = { Text(stringResource(R.string.recurrence_rrule_placeholder)) },
                supportingText = { Text(stringResource(R.string.recurrence_rrule_hint)) },
                modifier = Modifier.fillMaxWidth(),
            )
            return@Column
        }
        if (form.freq == Freq.NONE) return@Column

        val unitRes = when (form.freq) {
            Freq.DAILY -> R.plurals.recurrence_unit_day
            Freq.WEEKLY -> R.plurals.recurrence_unit_week
            Freq.MONTHLY -> R.plurals.recurrence_unit_month
            else -> R.plurals.recurrence_unit_year
        }
        val unit = pluralStringResource(unitRes, form.interval)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.recurrence_every))
            OutlinedTextField(
                value = form.interval.toString(),
                onValueChange = { v -> v.filter(Char::isDigit).take(3).toIntOrNull()?.let { onChange(form.copy(interval = it.coerceAtLeast(1))) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(80.dp),
            )
            Text(unit)
        }

        if (form.freq == Freq.WEEKLY) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (i in 0L until 7L) {
                    val day = weekStart.plus(i)
                    FilterChip(
                        selected = day in form.byDay,
                        onClick = {
                            val next = if (day in form.byDay) form.byDay - day else form.byDay + day
                            onChange(form.copy(byDay = next))
                        },
                        label = { Text(day.shortName()) },
                    )
                }
            }
        }

        if (form.freq == Freq.MONTHLY) {
            val ordinal = weekdayOrdinal(start)
            val weekdayText = stringResource(
                R.string.rrule_monthly_on_weekday,
                ordinalText(ordinal).asString(),
                start.dayOfWeek.getDisplayName(TextStyle.FULL, LOCALE),
            )
            RadioRow(stringResource(R.string.rrule_monthly_on_day, start.dayOfMonth), form.monthlyMode == MonthlyMode.DAY) { onChange(form.copy(monthlyMode = MonthlyMode.DAY)) }
            RadioRow(weekdayText, form.monthlyMode == MonthlyMode.WEEKDAY) { onChange(form.copy(monthlyMode = MonthlyMode.WEEKDAY)) }
        }

        Text(stringResource(R.string.recurrence_ends), style = MaterialTheme.typography.labelLarge)
        RadioRow(stringResource(R.string.recurrence_never), form.endMode == EndMode.NEVER) { onChange(form.copy(endMode = EndMode.NEVER)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            RadioRow(stringResource(R.string.recurrence_on), form.endMode == EndMode.UNTIL) {
                onChange(form.copy(endMode = EndMode.UNTIL, until = form.until ?: start.plusMonths(3)))
            }
            if (form.endMode == EndMode.UNTIL) {
                TextButton(onClick = { pickUntil = true }) {
                    Text((form.until ?: start.plusMonths(3)).format(FMT_DATE_SHORT))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RadioRow(stringResource(R.string.recurrence_after), form.endMode == EndMode.COUNT) { onChange(form.copy(endMode = EndMode.COUNT)) }
            if (form.endMode == EndMode.COUNT) {
                OutlinedTextField(
                    value = form.count.toString(),
                    onValueChange = { v -> v.filter(Char::isDigit).take(4).toIntOrNull()?.let { onChange(form.copy(count = it.coerceAtLeast(1))) } },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(90.dp),
                )
                Text(stringResource(R.string.recurrence_occurrences))
            }
        }

        buildRRule(form, start)?.let { rule ->
            Text(
                describeRRule(rule, start).asString(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }

    if (pickUntil) {
        DatePickerModal(
            initial = form.until ?: start.plusMonths(3),
            onPick = { onChange(form.copy(until = it)) },
            onDismiss = { pickUntil = false },
        )
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.selectable(selected = selected, onClick = onClick, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(label, Modifier.padding(start = 4.dp, end = 12.dp))
    }
}
