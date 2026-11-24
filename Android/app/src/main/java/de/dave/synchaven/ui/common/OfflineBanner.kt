package de.dave.synchaven.ui.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.CloudSync
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import de.dave.synchaven.R
import de.dave.synchaven.data.offline.OfflineStatus
import de.dave.synchaven.data.offline.OfflineSync
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/** State of the offline mode for [OfflineBanner] */
class OfflineStatusViewModel(private val sync: OfflineSync) : ViewModel() {
    val status: StateFlow<OfflineStatus> = sync.status

    /** App in the foreground: sync if needed */
    fun onForeground() = sync.syncIfNeeded()

    /** Periodically in the foreground: retry the server as long as it is unreachable or changes are pending */
    fun retryIfNeeded() {
        val s = status.value
        if ((s.offline || s.pending > 0) && !s.syncing) sync.requestSync()
    }

    fun syncNow() = sync.requestSync()
    fun dismissRejected() = sync.dismissRejected()
}

/**
 * Notice above the navigation bar: offline (stored data), pending changes or a change rejected by the
 * server. As long as the app is visible, the server is retried every minute.
 */
@Composable
fun OfflineBanner() {
    val vm = appViewModel { OfflineStatusViewModel(it.offlineSync) }
    val s by vm.status.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.onForeground()
            while (true) {
                delay(60_000)
                vm.retryIfNeeded()
            }
        }
    }

    val rejected = s.rejected
    AnimatedVisibility(visible = rejected != null || s.offline || s.pending > 0) {
        when {
            rejected != null -> BannerRow(
                icon = Icons.Outlined.ErrorOutline,
                text = stringResource(R.string.offline_rejected, rejected.asString()),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                onClick = null,
                onClose = vm::dismissRejected,
            )
            s.offline -> BannerRow(
                icon = Icons.Outlined.CloudOff,
                text = buildString {
                    append(stringResource(R.string.offline_banner))
                    if (s.pending > 0) append(" · ").append(pluralStringResource(R.plurals.offline_pending, s.pending, s.pending))
                },
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = vm::syncNow,
            )
            else -> BannerRow(
                icon = Icons.Outlined.CloudSync,
                text = pluralStringResource(R.plurals.offline_uploading, s.pending, s.pending),
                container = MaterialTheme.colorScheme.secondaryContainer,
                content = MaterialTheme.colorScheme.onSecondaryContainer,
                onClick = vm::syncNow,
            )
        }
    }
}

@Composable
private fun BannerRow(
    icon: ImageVector,
    text: String,
    container: Color,
    content: Color,
    onClick: (() -> Unit)?,
    onClose: (() -> Unit)? = null,
) {
    Surface(color = container, contentColor = content, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
                .padding(start = 16.dp, end = if (onClose != null) 4.dp else 16.dp, top = 6.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (onClose != null) {
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.action_close), Modifier.size(18.dp))
                }
            }
        }
    }
}
