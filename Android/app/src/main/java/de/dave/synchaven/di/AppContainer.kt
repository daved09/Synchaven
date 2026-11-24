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

/** Simple manual dependency container. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dataStore = AppDataStore(context, appScope)
    val sessionStore = SessionStore(dataStore)
    val settingsStore = SettingsStore(dataStore)
    val syncController = SyncController(context)

    // A 401 with a signed-in session means: token expired → back to sign-in
    private val httpClient = NetworkFactory.okHttp(sessionStore, BuildConfig.DEBUG) {
        appScope.launch { sessionStore.clear() }
    }
    /** Local cache of all data of the signed-in account, so the app also works offline */
    val offlineStore = OfflineStore(context, appScope)

    /** "Server" of the local mode – also for the sync with Android (see sync/SyncApi.kt) */
    val localApi = LocalApi(offlineStore) { sessionStore.current?.defaultView?.apiValue ?: ViewMode.MONTH.apiValue }
    private val api = SessionApi(NetworkFactory.api(httpClient), localApi) { sessionStore.current?.local == true }
    val offlineSync: OfflineSync = OfflineSync(
        context, api, offlineStore, sessionStore, appScope,
        // After transferring changes made offline
        onEventsChanged = { calendarRepository.eventsChanged() },
        onContactsChanged = { syncController.requestSync(SyncKind.CONTACTS) },
    )

    val authRepository = AuthRepository(
        api, sessionStore, syncController, offlineSync, offlineStore, context.getString(R.string.local_account_name),
    )
    val calendarRepository: CalendarRepository = CalendarRepository(api, offlineStore, offlineSync, appScope,
        onServerChanged = { syncController.requestSync(SyncKind.CALENDAR) })

    // Contact changes also change the birthday calendar
    val contactRepository = ContactRepository(api, offlineStore, offlineSync, appScope, onContactsChanged = {
        calendarRepository.birthdaysChanged()
        syncController.requestSync(SyncKind.CONTACTS)
    })
    /** Task lists and tasks (only with a server, not in local mode) */
    val taskRepository = TaskRepository(api, offlineStore, offlineSync, appScope)
    val fileTransfer = FileTransfer(context)

    init {
        // The cache belongs to the signed-in account
        appScope.launch {
            var lastUser: String? = "\u0000" // nothing opened yet
            sessionStore.state.collect { state ->
                val session = (state as? SessionState.LoggedIn)?.session
                val user = session?.let { if (it.local) LOCAL_ACCOUNT else it.serverUrl + "|" + it.userId }
                if (state !is SessionState.Loading && user != lastUser) {
                    offlineSync.reset()
                    offlineStore.open(user)
                    if (session?.local == true) runCatching { localApi.ensureInitialized() }
                    else if (user != null) offlineSync.requestSync()
                    lastUser = user
                    // Signed in, but no Android account yet (e.g. after an update): create it
                    if (user != null) runCatching { authRepository.ensureSyncAccount() }
                }
            }
        }
    }
}
