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
import de.dave.synchaven.ui.common.LoadingBox
import de.dave.synchaven.ui.common.needsLocalNetworkPermission
import de.dave.synchaven.ui.common.rememberLocalNetworkPermissionRequest
import de.dave.synchaven.ui.navigation.CalendarRoute
import de.dave.synchaven.ui.navigation.LoginRoute
import de.dave.synchaven.ui.navigation.SyncHavenNavGraph

/** Oberste Ebene: wartet auf die Sitzung, fragt die Berechtigung fürs lokale Netz an und zeigt die Navigation. */
@Composable
fun AppRoot(sessionStore: SessionStore) {
    val session by sessionStore.state.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        if (session == SessionState.Loading) {
            LoadingBox()
            return@Box
        }
        val loggedIn = session is SessionState.LoggedIn

        // Server im Heimnetz brauchen unter Android 17 die Berechtigung – vor der Anmeldung anfragen
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
    }
}

/**
 * Wechselt bei Anmeldung, Abmeldung oder abgelaufenem Token zwischen Anmeldung und App.
 * Läuft auch nach einer Konfigurationsänderung mit wiederhergestelltem Back-Stack, daher nur bei echtem Wechsel.
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
