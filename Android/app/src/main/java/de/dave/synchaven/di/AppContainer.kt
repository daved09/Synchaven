package de.dave.synchaven.di

import android.content.Context
import de.dave.synchaven.BuildConfig
import de.dave.synchaven.R
import de.dave.synchaven.data.AuthRepository
import de.dave.synchaven.data.CalendarRepository
import de.dave.synchaven.data.ContactRepository
import de.dave.synchaven.data.FileTransfer
import de.dave.synchaven.data.TaskRepository
import de.dave.synchaven.data.api.NetworkFactory
import de.dave.synchaven.data.api.SessionApi
import de.dave.synchaven.data.local.LOCAL_ACCOUNT
import de.dave.synchaven.data.local.LocalApi
import de.dave.synchaven.data.offline.OfflineStore
import de.dave.synchaven.data.offline.OfflineSync
import de.dave.synchaven.data.store.AppDataStore
import de.dave.synchaven.data.store.SessionState
import de.dave.synchaven.data.store.SessionStore
import de.dave.synchaven.data.store.SettingsStore
import de.dave.synchaven.data.store.ViewMode
import de.dave.synchaven.sync.SyncController
import de.dave.synchaven.sync.SyncKind
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
    val syncController = SyncController(context)

    // Ein 401 bei angemeldeter Sitzung bedeutet: Token abgelaufen → zur Anmeldung zurück
    private val httpClient = NetworkFactory.okHttp(sessionStore, BuildConfig.DEBUG) {
        appScope.launch { sessionStore.clear() }
    }
    /** Lokaler Cache aller Daten des angemeldeten Kontos, damit die App auch offline funktioniert */
    val offlineStore = OfflineStore(context, appScope)

    /** „Server“ des lokalen Modus – auch für die Synchronisierung mit Android (siehe sync/SyncApi.kt) */
    val localApi = LocalApi(offlineStore) { sessionStore.current?.defaultView?.apiValue ?: ViewMode.MONTH.apiValue }
    private val api = SessionApi(NetworkFactory.api(httpClient), localApi) { sessionStore.current?.local == true }
    val offlineSync: OfflineSync = OfflineSync(
        context, api, offlineStore, sessionStore, appScope,
        // Nach dem Übertragen offline gemachter Änderungen
        onEventsChanged = { calendarRepository.eventsChanged() },
        onContactsChanged = { syncController.requestSync(SyncKind.CONTACTS) },
    )

    val authRepository = AuthRepository(
        api, sessionStore, syncController, offlineSync, offlineStore, context.getString(R.string.local_account_name),
    )
    val calendarRepository: CalendarRepository = CalendarRepository(api, offlineStore, offlineSync, appScope,
        onServerChanged = { syncController.requestSync(SyncKind.CALENDAR) })

    // Kontaktänderungen ändern auch den Geburtstagskalender
    val contactRepository = ContactRepository(api, offlineStore, offlineSync, appScope, onContactsChanged = {
        calendarRepository.birthdaysChanged()
        syncController.requestSync(SyncKind.CONTACTS)
    })
    /** Aufgabenlisten und Aufgaben (nur mit Server, nicht im lokalen Modus) */
    val taskRepository = TaskRepository(api, offlineStore, offlineSync, appScope)
    val fileTransfer = FileTransfer(context)

    init {
        // Der Cache gehört zum angemeldeten Konto
        appScope.launch {
            var lastUser: String? = "\u0000" // noch nichts geöffnet
            sessionStore.state.collect { state ->
                val session = (state as? SessionState.LoggedIn)?.session
                val user = session?.let { if (it.local) LOCAL_ACCOUNT else it.serverUrl + "|" + it.userId }
                if (state !is SessionState.Loading && user != lastUser) {
                    offlineSync.reset()
                    offlineStore.open(user)
                    if (session?.local == true) runCatching { localApi.ensureInitialized() }
                    else if (user != null) offlineSync.requestSync()
                    lastUser = user
                    // Angemeldet, aber noch kein Android-Konto (z. B. nach einem Update): anlegen
                    if (user != null) runCatching { authRepository.ensureSyncAccount() }
                }
            }
        }
    }
}
