package de.dave.synchaven.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import de.dave.synchaven.R
import de.dave.synchaven.data.offline.OfflineStatus
import de.dave.synchaven.util.FMT_DATE_SHORT
import de.dave.synchaven.util.FMT_TIME
import java.time.ZoneId

/** Stand des lokalen Caches: letzter Abgleich, ausstehende Änderungen, Erreichbarkeit des Servers */
@Composable
internal fun OfflineSection(status: OfflineStatus, onSyncNow: () -> Unit) {
    SectionTitle(stringResource(R.string.offline_title))
    Text(
        stringResource(R.string.offline_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp),
    )
    ListItem(
        leadingContent = {
            Icon(
                when {
                    status.offline -> Icons.Outlined.CloudOff
                    status.syncing || status.pending > 0 -> Icons.Outlined.CloudSync
                    else -> Icons.Outlined.CloudDone
                },
                null,
            )
        },
        headlineContent = {
            val last = status.lastSync?.atZone(ZoneId.systemDefault())
            Text(
                when {
                    status.syncing -> stringResource(R.string.offline_syncing)
                    last != null -> stringResource(R.string.offline_last_sync, "${last.format(FMT_DATE_SHORT)}, ${last.format(FMT_TIME)}")
                    else -> stringResource(R.string.offline_never)
                }
            )
        },
        supportingContent = {
            Column {
                if (status.offline) {
                    val reason = status.reason
                    Text(
                        if (reason != null) stringResource(R.string.offline_unreachable, reason.asString())
                        else stringResource(R.string.offline_unreachable_plain),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                if (status.pending > 0) Text(pluralStringResource(R.plurals.offline_pending, status.pending, status.pending))
            }
        },
        trailingContent = {
            TextButton(onClick = onSyncNow, enabled = !status.syncing) { Text(stringResource(R.string.offline_sync_now)) }
        },
    )
}
