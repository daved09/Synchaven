package de.dave.synchaven.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import de.dave.synchaven.data.store.SessionState
import de.dave.synchaven.data.store.SessionStore
import de.dave.synchaven.data.store.SettingsStore
import de.dave.synchaven.reminders.ReminderNotifier
import android.Manifest
import android.os.Build
import de.dave.synchaven.ui.common.LoadingBox
import de.dave.synchaven.ui.common.needsLocalNetworkPermission
import de.dave.synchaven.ui.common.rememberLocalNetworkPermissionRequest
import de.dave.synchaven.ui.navigation.CalendarRoute
import de.dave.synchaven.ui.navigation.LoginRoute
import de.dave.synchaven.ui.navigation.SyncHavenNavGraph
import de.dave.synchaven.sync.SyncController
import de.dave.synchaven.sync.SyncKind
import de.dave.synchaven.sync.hasPermissions
import de.dave.synchaven.sync.permissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope

/** Top level: waits for the session, requests the local network permission and shows the navigation. */
@Composable
fun AppRoot(sessionStore: SessionStore, syncController: SyncController, settingsStore: SettingsStore) {
    val session by sessionStore.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (session == SessionState.Loading) {
            LoadingBox()
            return@Box
        }
        val loggedIn = session is SessionState.LoggedIn

        // Servers on the home network need the permission on Android 17 – request it before sign-in
        val context = LocalContext.current
        var permissionAsked by rememberSaveable { mutableStateOf(false) }
        val requestPermission = rememberLocalNetworkPermissionRequest { permissionAsked = true }
        if (!permissionAsked && needsLocalNetworkPermission(context)) {
            LaunchedEffect(Unit) { requestPermission() }
            LoadingBox()
            return@Box
        }

        val navController = rememberNavController()
        SessionNavigationEffect(navController, loggedIn)
        SyncHavenNavGraph(navController, startLoggedIn = loggedIn)
        if (loggedIn) {
            SyncOnboarding(syncController)
            NotificationOnboarding(syncController, settingsStore)
        }
    }
}

/**
 * Android 13+: ask once for the notification permission (reminders) – only after the
 * permissions for syncing, so two requests don't appear at the same time.
 */
@Composable
private fun NotificationOnboarding(sync: SyncController, settingsStore: SettingsStore) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val state by sync.state.collectAsStateWithLifecycle()
    val settings by settingsStore.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val waitForSync = state.accountName != null && !state.onboarded
    if (waitForSync || !settings.reminders || settings.notificationPermissionAsked) return
    LaunchedEffect(Unit) {
        scope.launch { settingsStore.update { it.copy(notificationPermissionAsked = true) } }
        if (!ReminderNotifier.canNotify(context)) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

/**
 * As soon as the Android account has been created (sign-in or update), ask once for the calendar and
 * contacts permissions and turn on the allowed areas – like after setting up an account in DAVx⁵.
 * This can be changed later under Settings → Sync with Android.
 */
@Composable
private fun SyncOnboarding(sync: SyncController) {
    val state by sync.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var asked by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        scope.launch(Dispatchers.IO) {
            for (kind in SyncKind.entries) if (context.hasPermissions(kind)) sync.enable(kind)
            sync.finishOnboarding()
        }
    }
    if (state.accountName == null || state.onboarded || asked) return
    LaunchedEffect(state.accountName) {
        asked = true
        launcher.launch(SyncKind.entries.flatMap { it.permissions.toList() }.toTypedArray())
    }
}

/**
 * Switches between sign-in and the app on sign-in, sign-out or an expired token.
 * Also runs after a configuration change with a restored back stack, so only on an actual switch.
 */
@Composable
private fun SessionNavigationEffect(navController: NavHostController, loggedIn: Boolean) {
    LaunchedEffect(loggedIn) {
        val current = navController.currentDestination ?: return@LaunchedEffect
        val onLogin = current.hasRoute(LoginRoute::class)
        if (loggedIn && onLogin) {
            navController.navigate(CalendarRoute) { popUpTo(0) { inclusive = true } }
        } else if (!loggedIn && !onLogin) {
            navController.navigate(LoginRoute) { popUpTo(0) { inclusive = true } }
        }
    }
}
