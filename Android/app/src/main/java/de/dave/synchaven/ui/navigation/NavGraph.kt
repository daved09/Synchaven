package de.dave.synchaven.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
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
            MainTab.CONTACTS -> ContactsRoute
            MainTab.SETTINGS -> SettingsRoute
        }
        navController.navigate(route) {
            popUpTo(CalendarRoute) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    NavHost(
        navController = navController,
        startDestination = if (startLoggedIn) CalendarRoute else LoginRoute,
        enterTransition = { fadeIn() },
        exitTransition = { fadeOut() },
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
                onOpenTasks = { navController.navigate(TasksRoute) { launchSingleTop = true } },
            )
        }
        composable<TasksRoute> {
            TasksScreen(onBack = { navController.popBackStack() })
        }
        composable<ContactsRoute>(enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }) {
            ContactsScreen(
                onSelectTab = selectTab,
                onOpenContact = { navController.navigate(ContactDetailRoute(it)) },
                onNewContact = { bookId, category ->
                    navController.navigate(ContactEditRoute(addressBookId = bookId, category = category))
                },
            )
        }
        composable<SettingsRoute>(enterTransition = { EnterTransition.None }, exitTransition = { ExitTransition.None }) {
            SettingsScreen(onSelectTab = selectTab)
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
