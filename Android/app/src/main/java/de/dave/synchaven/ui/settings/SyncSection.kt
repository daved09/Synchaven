package de.dave.synchaven.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import de.dave.synchaven.R
import de.dave.synchaven.sync.KindSyncState
import de.dave.synchaven.sync.SyncKind
import de.dave.synchaven.sync.SyncState
import de.dave.synchaven.sync.hasPermissions
import de.dave.synchaven.sync.permissions
import de.dave.synchaven.ui.common.ConfirmDialog
import de.dave.synchaven.util.FMT_DATE_SHORT
import de.dave.synchaven.util.FMT_TIME
import java.time.Instant
import java.time.ZoneId

/** Auswahl für das Intervall in Minuten, 0 = nur bei Änderungen und von Hand */
private val INTERVALS = listOf(15, 30, 60, 120, 240, 720, 1440, 0)

@Composable
private fun intervalLabel(minutes: Int): String = when {
    minutes == 0 -> stringResource(R.string.sync_interval_manual)
    minutes == 1440 -> stringResource(R.string.sync_interval_daily)
    minutes % 60 == 0 -> pluralStringResource(R.plurals.sync_interval_hours, minutes / 60, minutes / 60)
    else -> pluralStringResource(R.plurals.sync_interval_minutes, minutes, minutes)
}

/** Abschnitt „Mit Android synchronisieren“ der Einstellungen. */
@Composable
fun SyncSection(
    state: SyncState,
    onSetup: () -> Unit,
    onEnable: (SyncKind) -> Unit,
    onDisable: (SyncKind) -> Unit,
    onPermissionDenied: () -> Unit,
    onInterval: (Int) -> Unit,
    onSyncNow: () -> Unit,
) {
    val context = LocalContext.current
    var pendingKind by rememberSaveable { mutableStateOf<SyncKind?>(null) }
    var confirmDisable by rememberSaveable { mutableStateOf<SyncKind?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val kind = pendingKind ?: return@rememberLauncherForActivityResult
        pendingKind = null
        if (res.values.all { it }) onEnable(kind) else onPermissionDenied()
    }
    fun requestEnable(kind: SyncKind) {
        if (context.hasPermissions(kind)) onEnable(kind)
        else {
            pendingKind = kind
            permissionLauncher.launch(kind.permissions)
        }
    }

    SectionTitle(stringResource(R.string.sync_title))
    if (state.accountName == null) {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.Sync, null) },
            headlineContent = { Text(stringResource(R.string.sync_setup)) },
            supportingContent = { Text(stringResource(R.string.sync_intro)) },
            modifier = Modifier.clickable(onClick = onSetup),
        )
        return
    }

    for (kind in SyncKind.entries) {
        val s = state[kind]
        ListItem(
            leadingContent = {
                Icon(if (kind == SyncKind.CALENDAR) Icons.Outlined.CalendarMonth else Icons.Outlined.Contacts, null)
            },
            headlineContent = {
                Text(stringResource(if (kind == SyncKind.CALENDAR) R.string.sync_calendar else R.string.sync_contacts))
            },
            supportingContent = { SyncStatusText(s) },
            trailingContent = {
                Switch(s.enabled, onCheckedChange = { on -> if (on) requestEnable(kind) else confirmDisable = kind })
            },
            // Fehlt eine Berechtigung, holt ein Tipp sie erneut ein
            modifier = Modifier.clickable {
                if (!s.enabled) requestEnable(kind)
                else if (!context.hasPermissions(kind)) requestEnable(kind)
                else onSyncNow()
            },
        )
    }

    val anyEnabled = state.calendar.enabled || state.contacts.enabled
    val authError = stringResource(R.string.sync_error_auth)
    if (state.calendar.error == authError || state.contacts.error == authError) {
        ListItem(
            leadingContent = { Icon(Icons.Outlined.Key, null, tint = MaterialTheme.colorScheme.error) },
            headlineContent = { Text(stringResource(R.string.sync_reenter_password)) },
            modifier = Modifier.clickable(onClick = onSetup),
        )
    }
    if (anyEnabled) {
        ChoiceItem(
            title = stringResource(R.string.sync_interval),
            value = intervalLabel(state.intervalMinutes),
            options = INTERVALS.map { it to intervalLabel(it) },
            onSelect = onInterval,
            leadingIcon = { Icon(Icons.Outlined.Schedule, null) },
        )
        ListItem(
            leadingContent = { Icon(Icons.Outlined.Sync, null) },
            headlineContent = { Text(stringResource(R.string.sync_now)) },
            modifier = Modifier.clickable(onClick = onSyncNow),
        )
    } else {
        Text(
            stringResource(R.string.sync_intro),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
    }

    confirmDisable?.let { kind ->
        val calendar = kind == SyncKind.CALENDAR
        ConfirmDialog(
            title = stringResource(
                R.string.sync_disable_title,
                stringResource(if (calendar) R.string.sync_calendar else R.string.sync_contacts),
            ),
            text = stringResource(if (calendar) R.string.sync_disable_calendar_text else R.string.sync_disable_contacts_text),
            confirmLabel = stringResource(R.string.sync_disable_confirm),
            onConfirm = { onDisable(kind) },
            onDismiss = { confirmDisable = null },
        )
    }
}

@Composable
private fun SyncStatusText(s: KindSyncState) {
    val error = s.error
    when {
        !s.enabled -> Text(stringResource(R.string.sync_off))
        s.syncing -> Text(stringResource(R.string.sync_running))
        error != null -> Text(error, color = MaterialTheme.colorScheme.error)
        s.lastSync != null -> {
            val t = Instant.ofEpochMilli(s.lastSync).atZone(ZoneId.systemDefault())
            Text(stringResource(R.string.sync_last, "${t.format(FMT_DATE_SHORT)}, ${t.format(FMT_TIME)}"))
        }
        else -> Text(stringResource(R.string.sync_pending))
    }
}

/** Passwort für das Android-Konto, wenn sich die Synchronisierung nicht mehr anmelden kann. */
@Composable
fun SyncSetupDialog(
    form: SyncSetupForm,
    email: String,
    onPasswordChange: (String) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.sync_reenter_password)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sync_setup_hint, email), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    form.password, onPasswordChange,
                    label = { Text(stringResource(R.string.login_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { onSubmit() }),
                )
                form.error?.let { Text(it.asString(), color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            FilledTonalButton(onClick = onSubmit, enabled = !form.saving && form.password.isNotEmpty()) {
                Text(stringResource(R.string.login_tab_login))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
