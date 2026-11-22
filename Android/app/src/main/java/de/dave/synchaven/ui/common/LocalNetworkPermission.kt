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

/** API-Level von Android 17, das die Laufzeitberechtigung ACCESS_LOCAL_NETWORK eingeführt hat. */
private const val ANDROID_17 = 37

/** true, wenn die App unter Android 17+ (noch) nicht ins lokale Netz darf. */
fun needsLocalNetworkPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= ANDROID_17 &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) !=
        PackageManager.PERMISSION_GRANTED

/**
 * Liefert eine Funktion, die ACCESS_LOCAL_NETWORK bei Bedarf anfragt und danach [onResult] aufruft
 * (sofort mit `true`, wenn die Berechtigung schon erteilt oder nicht nötig ist).
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
