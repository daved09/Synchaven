package de.dave.synchaven.ui.calendar

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.dave.synchaven.R
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.ui.common.ConfirmDialog
import de.dave.synchaven.ui.theme.parseHexColor
import de.dave.synchaven.util.FMT_DATE_SHORT
import de.dave.synchaven.util.Reminders
import de.dave.synchaven.util.ViewEvent
import de.dave.synchaven.util.describeRRule
import de.dave.synchaven.util.describeSpan
import de.dave.synchaven.ui.viewmodels.DeleteScope

/**
 * Details eines Termins mit Bearbeiten und Löschen (bei Serien: dieses Vorkommen oder die ganze Serie).
 * Geburtstage sind schreibgeschützt und verweisen stattdessen auf den Kontakt.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDetailsSheet(
    ev: ViewEvent,
    calendar: Calendar?,
    onEdit: () -> Unit,
    onDelete: (DeleteScope) -> Unit,
    onOpenContact: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var askDelete by remember { mutableStateOf(false) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).background(parseHexColor(ev.color), RoundedCornerShape(4.dp)))
                Spacer(Modifier.size(16.dp))
                Text(ev.title, style = MaterialTheme.typography.headlineSmall)
            }
            DetailRow(Icons.Outlined.Schedule, describeSpan(ev.start, ev.end, ev.allDay).asString())
            if (ev.occ.recurring) {
                DetailRow(Icons.Outlined.Repeat, describeRRule(ev.occ.rrule, ev.start.toLocalDate()).asString())
            }
            if (ev.occ.location.isNotBlank()) {
                DetailRow(Icons.Outlined.LocationOn, ev.occ.location) {
                    val uri = "geo:0,0?q=${android.net.Uri.encode(ev.occ.location)}".toUri()
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                }
            }
            if (ev.occ.description.isNotBlank()) DetailRow(Icons.AutoMirrored.Outlined.Notes, ev.occ.description)
            // Wann benachrichtigt wird: eigene Erinnerungen des Termins oder der Standard des Kalenders
            val reminders = Reminders.sorted(ev.occ.reminders ?: calendar?.defaultReminders(ev.allDay).orEmpty())
            if (reminders.isNotEmpty()) {
                DetailRow(Icons.Outlined.NotificationsNone, reminders.joinToString("\n") { Reminders.describe(context.resources, it, ev.allDay) })
            }
            calendar?.let { DetailRow(Icons.Outlined.CalendarMonth, it.name) }

            val contactId = ev.occ.contactId
            if (ev.occ.readOnly) {
                // Geburtstage werden über den Kontakt bearbeitet.
                if (contactId != null) {
                    FilledTonalButton(onClick = { onOpenContact(contactId) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Icon(Icons.Outlined.Person, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(8.dp))
                        Text(stringResource(R.string.event_open_contact))
                    }
                }
            } else Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Outlined.Edit, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(if (ev.occ.recurring) R.string.event_edit_series else R.string.action_edit))
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
        if (ev.occ.recurring) {
            AlertDialog(
                onDismissRequest = { askDelete = false },
                title = { Text(stringResource(R.string.dialog_delete_series_title)) },
                text = { Text(stringResource(R.string.dialog_delete_series_text, ev.start.format(FMT_DATE_SHORT))) },
                confirmButton = {
                    Column(horizontalAlignment = Alignment.End) {
                        TextButton(onClick = { askDelete = false; onDelete(DeleteScope.OCCURRENCE) }) { Text(stringResource(R.string.delete_only_this)) }
                        TextButton(onClick = { askDelete = false; onDelete(DeleteScope.ALL) }) {
                            Text(stringResource(R.string.delete_whole_series), color = MaterialTheme.colorScheme.error)
                        }
                        TextButton(onClick = { askDelete = false }) { Text(stringResource(R.string.action_cancel)) }
                    }
                },
            )
        } else {
            ConfirmDialog(
                title = stringResource(R.string.dialog_delete_event_title),
                text = stringResource(R.string.dialog_delete_event_text, ev.title),
                onConfirm = { onDelete(DeleteScope.ALL) },
                onDismiss = { askDelete = false },
            )
        }
    }
}

@Composable
private fun DetailRow(icon: ImageVector, text: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Spacer(Modifier.size(16.dp))
        Text(
            text, style = MaterialTheme.typography.bodyLarge,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
    }
}
