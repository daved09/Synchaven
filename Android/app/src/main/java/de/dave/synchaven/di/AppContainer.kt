package de.dave.synchaven.di

import android.content.Context
import de.dave.synchaven.BuildConfig
import de.dave.synchaven.data.AuthRepository
import de.dave.synchaven.data.CalendarRepository
import de.dave.synchaven.data.ContactRepository
import de.dave.synchaven.data.FileTransfer
import de.dave.synchaven.data.api.NetworkFactory
import de.dave.synchaven.data.store.AppDataStore
import de.dave.synchaven.data.store.SessionState
import de.dave.synchaven.data.store.SessionStore
import de.dave.synchaven.data.store.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Einfacher manueller Abhängigkeitscontainer. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataStore = AppDataStore(context, appScope)
    val sessionStore = SessionStore(dataStore)
    val settingsStore = SettingsStore(dataStore)

    // Ein 401 bei angemeldeter Sitzung bedeutet: Token abgelaufen → zur Anmeldung zurück
    private val httpClient = NetworkFactory.okHttp(sessionStore, BuildConfig.DEBUG) {
        appScope.launch { sessionStore.clear() }
    }
    private val api = NetworkFactory.api(httpClient)

    val authRepository = AuthRepository(api, sessionStore)
    val calendarRepository = CalendarRepository(api)
    val contactRepository = ContactRepository(api, onContactsChanged = calendarRepository::eventsChanged)
    val fileTransfer = FileTransfer(context)

    init {
        // Zwischengespeicherte Daten gehören zum angemeldeten Konto
        appScope.launch {
            var lastUser: String? = null
            sessionStore.state.collect { state ->
                val user = (state as? SessionState.LoggedIn)?.session?.let { it.serverUrl + "|" + it.userId }
                if (state !is SessionState.Loading && user != lastUser) {
                    calendarRepository.reset()
                    contactRepository.reset()
                    lastUser = user
                }
            }
        }
    }
}
