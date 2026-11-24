package de.dave.synchaven.ui.navigation

import androidx.activity.BackEventCompat
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import de.dave.synchaven.ui.screens.CalendarScreen
import de.dave.synchaven.ui.common.MainTab
import de.dave.synchaven.ui.screens.ContactDetailScreen
import de.dave.synchaven.ui.screens.ContactEditScreen
import de.dave.synchaven.ui.screens.ContactsScreen
import de.dave.synchaven.ui.screens.EventEditScreen
import de.dave.synchaven.ui.screens.LoginScreen
import de.dave.synchaven.ui.screens.SettingsScreen
import de.dave.synchaven.ui.screens.TasksScreen

@Composable
fun SyncHavenNavGraph(navController: NavHostController, startLoggedIn: Boolean) {
    // Wechsel zwischen den Hauptbereichen ersetzt den aktuellen Bereich; „Zurück“ führt zum Kalender
    val selectTab: (MainTab) -> Unit = { tab ->
        val route: Any = when (tab) {
            MainTab.CALENDAR -> CalendarRoute
            MainTab.TASKS -> TasksRoute
            MainTab.CONTACTS -> ContactsRoute
        }
        navController.navigate(route) {
            popUpTo(CalendarRoute) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    // Einstellungen liegen über dem aktuellen Bereich (aus dem ⋮-Menü), „Zurück“ kehrt dorthin zurück
    val openSettings: () -> Unit = { navController.navigate(SettingsRoute) { launchSingleTop = true } }
    NavHost(
        navController = navController,
        startDestination = if (startLoggedIn) CalendarRoute else LoginRoute,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
        // Zurück-Geste (Predictive Back, Material 3): Die aktuelle Seite schrumpft mit dem Finger und
        // rückt zur Wischseite, die vorherige liegt unbewegt dahinter; beim Abschließen blendet sie aus.
        predictivePopEnterTransition = { EnterTransition.None },
        predictivePopExitTransition = { swipeEdge ->
            val pivotX = if (swipeEdge == BackEventCompat.EDGE_LEFT) 0.8f else 0.2f
            scaleOut(targetScale = 0.9f, transformOrigin = TransformOrigin(pivotX, 0.5f)) +
                fadeOut(tween(easing = LateFade))
        },
    ) {
        composable<LoginRoute> { LoginScreen() }

        composable<CalendarRoute>(enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }) {
            CalendarScreen(
                onSelectTab = selectTab,
                onNewEvent = { start, allDay ->
                    navController.navigate(EventEditRoute(startEpochSecond = start, allDay = allDay))
                },
                onEditEvent = { id -> navController.navigate(EventEditRoute(eventId = id)) },
                onOpenContact = { id -> navController.navigate(ContactDetailRoute(id)) },
                onOpenSettings = openSettings,
            )
        }
        composable<TasksRoute>(enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }) {
            TasksScreen(onSelectTab = selectTab, onOpenSettings = openSettings)
        }
        composable<ContactsRoute>(enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }) {
            ContactsScreen(
                onSelectTab = selectTab,
                onOpenSettings = openSettings,
                onOpenContact = { navController.navigate(ContactDetailRoute(it)) },
                onNewContact = { bookId, category ->
                    navController.navigate(ContactEditRoute(addressBookId = bookId, category = category))
                },
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(onBack = { navController.popBackStack() })
        }

        composable<EventEditRoute> {
            EventEditScreen(onClose = { navController.popBackStack() })
        }
        composable<ContactDetailRoute> {
            ContactDetailScreen(
                onBack = { navController.popBackStack() },
                onEdit = { id -> navController.navigate(ContactEditRoute(id = id)) },
            )
        }
        composable<ContactEditRoute> {
            ContactEditScreen(
                onClose = { navController.popBackStack() },
                onCreated = { id ->
                    // Neuer Kontakt: Editor durch die Detailansicht ersetzen
                    navController.navigate(ContactDetailRoute(id)) {
                        popUpTo<ContactEditRoute> { inclusive = true }
                    }
                },
            )
        }
    }
}

/** Bleibt während der Geste fast deckend und blendet erst gegen Ende aus. */
private val LateFade = CubicBezierEasing(0.8f, 0f, 1f, 1f)
