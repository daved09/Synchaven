package de.dave.synchaven.ui.common

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

/** API level of Android 17, which introduced the ACCESS_LOCAL_NETWORK runtime permission. */
private const val ANDROID_17 = 37

/** true if the app is not (yet) allowed to access the local network on Android 17+. */
fun needsLocalNetworkPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= ANDROID_17 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
        PackageManager.PERMISSION_GRANTED

/**
 * Returns a function that requests ACCESS_LOCAL_NETWORK if needed and then calls [onResult]
 * (immediately with `true` if the permission is already granted or not needed).
 */
@Composable
fun rememberLocalNetworkPermissionRequest(onResult: (granted: Boolean) -> Unit): () -> Unit {
    val context = LocalContext.current
    val currentOnResult by rememberUpdatedState(onResult)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        currentOnResult(granted)
    }
    return remember(launcher, context) {
        {
            if (needsLocalNetworkPermission(context)) launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
            else currentOnResult(true)
        }
    }
}
