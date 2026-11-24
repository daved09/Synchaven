package de.dave.synchaven.ui.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.dave.synchaven.R
import de.dave.synchaven.reminders.ReminderNotifier

/** Turn reminders on/off; points out missing permissions. */
@Composable
fun NotificationSection(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    val context = LocalContext.current
    // Check again after returning from the Android settings
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        refresh++
        onPauseOrDispose { }
    }
    val canNotify = remember(refresh) { ReminderNotifier.canNotify(context) }
    val canExact = remember(refresh) { ReminderNotifier.canScheduleExact(context) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        refresh++
        onToggle(true)
    }

    SectionTitle(stringResource(R.string.settings_notifications))
    ListItem(
        leadingContent = { Icon(Icons.Outlined.NotificationsNone, null) },
        headlineContent = { Text(stringResource(R.string.settings_reminders)) },
        supportingContent = { Text(stringResource(R.string.settings_reminders_summary)) },
        trailingContent = {
            Switch(enabled, onCheckedChange = { on ->
                if (on && !canNotify && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    onToggle(on)
                }
            })
        },
    )
    if (enabled && !canNotify) {
        Hint(stringResource(R.string.settings_reminders_denied)) {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
            )
        }
    } else if (enabled && !canExact && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Hint(stringResource(R.string.settings_reminders_exact)) {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
            )
        }
    }
}

@Composable
private fun Hint(text: String, onOpen: () -> Unit) {
    Column(Modifier.padding(start = 72.dp, end = 16.dp, bottom = 8.dp)) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        TextButton(onClick = onOpen) { Text(stringResource(R.string.settings_reminders_open_settings)) }
    }
}
