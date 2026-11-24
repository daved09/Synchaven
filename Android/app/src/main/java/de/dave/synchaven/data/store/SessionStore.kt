package de.dave.synchaven.data.store

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import de.dave.synchaven.data.model.User
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class Session(
    val serverUrl: String,
    val token: String,
    val userId: String,
    val email: String,
    val name: String,
    /** The calendar's default view from the user account */
    val defaultView: ViewMode,
    /** Local mode: without a server, all data only on this device */
    val local: Boolean = false,
)

sealed interface SessionState {
    /** DataStore hasn't loaded yet */
    data object Loading : SessionState
    data object LoggedOut : SessionState
    data class LoggedIn(val session: Session) : SessionState
}

/** User ID of the session in local mode */
const val LOCAL_USER = "local"

/** Signed-in session (server, token, user) or local mode, and the most recently used sign-in data. */
class SessionStore(private val dataStore: AppDataStore) {
    private val store = dataStore.store

    private object Keys {
        val server = stringPreferencesKey("server_url")
        val token = stringPreferencesKey("token")
        val userId = stringPreferencesKey("user_id")
        val email = stringPreferencesKey("email")
        val name = stringPreferencesKey("name")
        val defaultView = stringPreferencesKey("user_default_view")
        val lastServer = stringPreferencesKey("last_server")
        val lastEmail = stringPreferencesKey("last_email")
        /** `<server>|<user>` for which the Android account was created – if it is missing, the user removed it */
        val syncAccountFor = stringPreferencesKey("sync_account_for")
        /** `1` in local mode without a server */
        val localMode = stringPreferencesKey("local_mode")
    }

    val state: StateFlow<SessionState> = dataStore.prefs
        .map { it?.toSessionState() ?: SessionState.Loading }
        .stateIn(dataStore.scope, SharingStarted.Eagerly, SessionState.Loading)

    /** Synchronous access for the OkHttp interceptor */
    val current: Session?
        get() = (state.value as? SessionState.LoggedIn)?.session

    /** Most recently used server and email address, prefilled on the sign-in screen */
    val lastLogin: Flow<Pair<String, String>> =
        store.data.map { (it[Keys.lastServer] ?: "") to (it[Keys.lastEmail] ?: "") }

    suspend fun save(serverUrl: String, token: String, user: User) {
        store.edit {
            it.remove(Keys.localMode)
            it[Keys.server] = serverUrl
            it[Keys.token] = token
            it[Keys.lastServer] = serverUrl
            it[Keys.lastEmail] = user.email
            it.putUser(user)
        }
    }

    /** Start local mode: without a server, without sign-in. */
    suspend fun startLocal() {
        store.edit {
            it[Keys.localMode] = "1"
            if (it[Keys.defaultView] == null) it[Keys.defaultView] = ViewMode.MONTH.apiValue
        }
    }

    suspend fun updateUser(user: User) {
        store.edit {
            it.putUser(user)
            it[Keys.lastEmail] = user.email
        }
    }

    /** Set the default view locally (e.g. offline, before it has been transferred to the server) */
    suspend fun setDefaultView(mode: ViewMode) {
        store.edit { it[Keys.defaultView] = mode.apiValue }
    }

    /** The Android account has already been created for this session (and possibly removed again by the user). */
    suspend fun syncAccountCreated(session: Session): Boolean =
        store.data.first()[Keys.syncAccountFor] == "${session.serverUrl}|${session.userId}"

    suspend fun markSyncAccountCreated(serverUrl: String, userId: String) {
        store.edit { it[Keys.syncAccountFor] = "$serverUrl|$userId" }
    }

    suspend fun clear() {
        store.edit {
            it.remove(Keys.syncAccountFor)
            it.remove(Keys.server)
            it.remove(Keys.token)
            it.remove(Keys.userId)
            it.remove(Keys.email)
            it.remove(Keys.name)
            it.remove(Keys.defaultView)
            it.remove(Keys.localMode)
        }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.putUser(user: User) {
        this[Keys.userId] = user.id
        this[Keys.email] = user.email
        this[Keys.name] = user.name
        this[Keys.defaultView] = user.defaultView
    }

    private fun Preferences.toSessionState(): SessionState {
        if (this[Keys.localMode] == "1") {
            return SessionState.LoggedIn(
                Session(
                    serverUrl = "", token = "", userId = LOCAL_USER, email = "", name = "",
                    defaultView = ViewMode.fromApi(this[Keys.defaultView]), local = true,
                )
            )
        }
        val server = this[Keys.server]
        val token = this[Keys.token]
        if (server.isNullOrBlank() || token.isNullOrBlank()) return SessionState.LoggedOut
        return SessionState.LoggedIn(
            Session(
                serverUrl = server,
                token = token,
                userId = this[Keys.userId].orEmpty(),
                email = this[Keys.email].orEmpty(),
                name = this[Keys.name].orEmpty(),
                defaultView = ViewMode.fromApi(this[Keys.defaultView]),
            )
        )
    }
}
