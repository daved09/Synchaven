package de.dave.synchaven.data

import de.dave.synchaven.data.api.ApiException
import de.dave.synchaven.data.api.NoServerConfiguredException
import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.api.apiCall
import de.dave.synchaven.data.api.normalizeServerUrl
import de.dave.synchaven.data.model.AuthResponse
import de.dave.synchaven.data.model.LoginRequest
import de.dave.synchaven.data.model.RegisterRequest
import de.dave.synchaven.data.model.ServerInfo
import de.dave.synchaven.data.model.UpdateMeRequest
import de.dave.synchaven.data.model.User
import de.dave.synchaven.data.offline.OfflineStore
import de.dave.synchaven.data.offline.OfflineSync
import de.dave.synchaven.data.offline.PendingOp
import de.dave.synchaven.data.store.SessionStore
import de.dave.synchaven.data.store.ViewMode
import de.dave.synchaven.sync.SyncController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Error code of a sign-in with the correct password to an account with two-factor authentication (see docs/API.md) */
const val TOTP_REQUIRED = "totp_required"

/** Error code of a wrong or already used code of the second factor */
const val TOTP_INVALID = "totp_invalid"

/** Sign-in, registration and user account. */
class AuthRepository(
    private val api: SyncHavenApi,
    private val sessionStore: SessionStore,
    private val sync: SyncController,
    private val offline: OfflineSync,
    private val offlineStore: OfflineStore,
    /** Name of the Android account in local mode (appears in the calendar and in the contacts) */
    private val localAccountName: String,
) {
    private val isLocal get() = sessionStore.current?.local == true

    /**
     * Use without server: calendars and contacts are stored only on this device (see LocalApi). Data from an
     * earlier local mode is back. The navigation reacts to the new session state.
     */
    suspend fun startLocal() {
        sessionStore.startLocal()
    }

    suspend fun serverInfo(server: String): ServerInfo = apiCall { api.info(normalizeServerUrl(server)) }

    suspend fun currentServerInfo(): ServerInfo = apiCall { api.currentInfo() }

    /**
     * Signs in and stores the session; the navigation reacts to the new session state.
     * Accounts with two-factor authentication need [code]; without it the server answers with [TOTP_REQUIRED].
     */
    suspend fun login(server: String, email: String, password: String, code: String? = null) {
        val url = normalizeServerUrl(server)
        val res = apiCall { api.login(url, LoginRequest(email.trim(), password, code?.trim()?.ifEmpty { null })) }
        // With two-factor authentication the password alone can't sign in again: the sync only renews its token
        val syncPassword = password.takeUnless { res.user.totpEnabled }
        // Sign-in should not fail because of this; sync can be set up later
        runCatching { setupSyncAccount(url, syncPassword, res) }
        sessionStore.save(url, res.token, res.user)
    }

    suspend fun register(server: String, email: String, password: String, name: String) {
        val url = normalizeServerUrl(server)
        val res = apiCall { api.register(url, RegisterRequest(email.trim(), password, name.trim())) }
        // Sign-in should not fail because of this; sync can be set up later
        runCatching { setupSyncAccount(url, password, res) }
        sessionStore.save(url, res.token, res.user)
    }

    /**
     * Creates the Android account for an existing session (e.g. after updating to a version with sync) –
     * without a password, with its own token that the sync renews itself.
     * If the user removed the account in the Android settings, this only happens with [force]
     * (e.g. "Add account" in the Android settings).
     */
    suspend fun ensureSyncAccount(force: Boolean = false) {
        val s = sessionStore.current ?: return
        if (withContext(Dispatchers.IO) { sync.hasAccountFor(s.serverUrl, s.userId) }) return
        if (!force && sessionStore.syncAccountCreated(s)) return
        if (s.local) {
            // Without a server there is no token: the sync reads and writes the local data directly
            withContext(Dispatchers.IO) { sync.setupAccount(s.serverUrl, s.userId, localAccountName, null, "", "") }
            sessionStore.markSyncAccountCreated(s.serverUrl, s.userId)
            return
        }
        val res = try {
            apiCall { api.refreshToken() }
        } catch (e: ApiException) {
            // Server without /auth/refresh: use the session's token (expires along with it)
            if (e.status != 404) throw e
            AuthResponse(token = s.token, expiresAt = "", user = User(s.userId, s.email, s.name))
        }
        withContext(Dispatchers.IO) {
            sync.setupAccount(s.serverUrl, res.user.id, res.user.email, null, res.token, res.expiresAt)
        }
        sessionStore.markSyncAccountCreated(s.serverUrl, res.user.id)
    }

    /**
     * Sets up the Android account for syncing if the session doesn't have one yet (e.g. after
     * an app update) or the stored password no longer matches. Checks the password with the server.
     */
    suspend fun setupSync(password: String) {
        val session = sessionStore.current ?: throw NoServerConfiguredException()
        try {
            val res = apiCall { api.login(session.serverUrl, LoginRequest(session.email, password)) }
            setupSyncAccount(session.serverUrl, password, res)
        } catch (e: ApiException) {
            if (e.code != TOTP_REQUIRED) throw e
            // The password is correct, but the account has two-factor authentication: the sync gets its own token
            // from the session instead and renews it itself, without a password
            val res = apiCall { api.refreshToken() }
            setupSyncAccount(session.serverUrl, null, res)
        }
    }

    private suspend fun setupSyncAccount(server: String, password: String?, res: AuthResponse) {
        withContext(Dispatchers.IO) {
            sync.setupAccount(server, res.user.id, res.user.email, password, res.token, res.expiresAt)
        }
        sessionStore.markSyncAccountCreated(server, res.user.id)
    }

    /** User account from the server; in local mode there is none (`null`). */
    suspend fun me(): User? = if (isLocal) null else apiCall { api.me() }.also { sessionStore.updateUser(it) }

    suspend fun updateMe(request: UpdateMeRequest): User {
        val user = apiCall { api.updateMe(request) }
        sessionStore.updateUser(user)
        // The Android account signs in again with email and password when its token expires
        withContext(Dispatchers.IO) {
            request.newPassword?.let(sync::updatePassword)
            if (request.email != null) sync.updateEmail(user.email)
        }
        return user
    }

    /** Saves the calendar's default view in the user account; without a connection it is remembered locally and transferred later */
    suspend fun setDefaultView(mode: ViewMode) {
        if (isLocal) {
            sessionStore.setDefaultView(mode)
            return
        }
        offline.write(
            PendingOp.SetDefaultView(mode.apiValue),
            online = { updateMe(UpdateMeRequest(defaultView = mode.apiValue)) },
            offline = { },
        )
        sessionStore.setDefaultView(mode)
    }

    /**
     * Signs out and removes the Android account including synced calendars and contacts from the device, as well as the
     * offline cache with changes not yet transferred.
     */
    suspend fun logout() {
        withContext(Dispatchers.IO) { sync.removeAccount() }
        // In local mode this is the only data: it is kept until local mode starts again
        if (!isLocal) offlineStore.deleteCurrent()
        sessionStore.clear()
    }
}
