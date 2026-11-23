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

/** Anmeldung, Registrierung und Benutzerkonto. */
class AuthRepository(
    private val api: SyncHavenApi,
    private val sessionStore: SessionStore,
    private val sync: SyncController,
    private val offline: OfflineSync,
    private val offlineStore: OfflineStore,
    /** Name des Android-Kontos im lokalen Modus (erscheint im Kalender und in den Kontakten) */
    private val localAccountName: String,
) {
    private val isLocal get() = sessionStore.current?.local == true

    /**
     * Ohne Server verwenden: Kalender und Kontakte liegen nur auf diesem Gerät (siehe LocalApi). Daten aus einem
     * früheren lokalen Modus sind wieder da. Die Navigation reagiert auf den neuen Sitzungszustand.
     */
    suspend fun startLocal() {
        sessionStore.startLocal()
    }

    suspend fun serverInfo(server: String): ServerInfo = apiCall { api.info(normalizeServerUrl(server)) }

    suspend fun currentServerInfo(): ServerInfo = apiCall { api.currentInfo() }

    /** Meldet an und speichert die Sitzung; die Navigation reagiert auf den neuen Sitzungszustand. */
    suspend fun login(server: String, email: String, password: String) {
        val url = normalizeServerUrl(server)
        val res = apiCall { api.login(url, LoginRequest(email.trim(), password)) }
        // Die Anmeldung soll nicht daran scheitern; die Synchronisierung lässt sich später einrichten
        runCatching { setupSyncAccount(url, password, res) }
        sessionStore.save(url, res.token, res.user)
    }

    suspend fun register(server: String, email: String, password: String, name: String) {
        val url = normalizeServerUrl(server)
        val res = apiCall { api.register(url, RegisterRequest(email.trim(), password, name.trim())) }
        // Die Anmeldung soll nicht daran scheitern; die Synchronisierung lässt sich später einrichten
        runCatching { setupSyncAccount(url, password, res) }
        sessionStore.save(url, res.token, res.user)
    }

    /**
     * Legt für eine bestehende Sitzung (z. B. nach dem Update auf eine Version mit Synchronisierung) das
     * Android-Konto an – ohne Passwort, mit eigenem Token, das die Synchronisierung selbst erneuert.
     * Hat der Benutzer das Konto in den Android-Einstellungen entfernt, geschieht das nur mit [force]
     * (z. B. „Konto hinzufügen“ in den Android-Einstellungen).
     */
    suspend fun ensureSyncAccount(force: Boolean = false) {
        val s = sessionStore.current ?: return
        if (withContext(Dispatchers.IO) { sync.hasAccountFor(s.serverUrl, s.userId) }) return
        if (!force && sessionStore.syncAccountCreated(s)) return
        if (s.local) {
            // Ohne Server gibt es kein Token: die Synchronisierung liest und schreibt direkt die lokalen Daten
            withContext(Dispatchers.IO) { sync.setupAccount(s.serverUrl, s.userId, localAccountName, null, "", "") }
            sessionStore.markSyncAccountCreated(s.serverUrl, s.userId)
            return
        }
        val res = try {
            apiCall { api.refreshToken() }
        } catch (e: ApiException) {
            // Server ohne /auth/refresh: Token der Sitzung verwenden (läuft wie diese ab)
            if (e.status != 404) throw e
            AuthResponse(token = s.token, expiresAt = "", user = User(s.userId, s.email, s.name))
        }
        withContext(Dispatchers.IO) {
            sync.setupAccount(s.serverUrl, res.user.id, res.user.email, null, res.token, res.expiresAt)
        }
        sessionStore.markSyncAccountCreated(s.serverUrl, res.user.id)
    }

    /**
     * Richtet das Android-Konto für die Synchronisierung ein, wenn die Sitzung noch keines hat (z. B. nach
     * einem App-Update) oder das gespeicherte Passwort nicht mehr stimmt. Prüft das Passwort beim Server.
     */
    suspend fun setupSync(password: String) {
        val session = sessionStore.current ?: throw NoServerConfiguredException()
        val res = apiCall { api.login(session.serverUrl, LoginRequest(session.email, password)) }
        setupSyncAccount(session.serverUrl, password, res)
    }

    private suspend fun setupSyncAccount(server: String, password: String, res: AuthResponse) {
        withContext(Dispatchers.IO) {
            sync.setupAccount(server, res.user.id, res.user.email, password, res.token, res.expiresAt)
        }
        sessionStore.markSyncAccountCreated(server, res.user.id)
    }

    /** Benutzerkonto vom Server; im lokalen Modus gibt es keins (`null`). */
    suspend fun me(): User? = if (isLocal) null else apiCall { api.me() }.also { sessionStore.updateUser(it) }

    suspend fun updateMe(request: UpdateMeRequest): User {
        val user = apiCall { api.updateMe(request) }
        sessionStore.updateUser(user)
        // Das Android-Konto meldet sich mit E-Mail und Passwort neu an, wenn sein Token abläuft
        withContext(Dispatchers.IO) {
            request.newPassword?.let(sync::updatePassword)
            if (request.email != null) sync.updateEmail(user.email)
        }
        return user
    }

    /** Speichert die Standardansicht des Kalenders im Benutzerkonto; ohne Verbindung lokal gemerkt und später übertragen */
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
     * Meldet ab und entfernt das Android-Konto samt synchronisierter Kalender und Kontakte vom Gerät, ebenso den
     * Offline-Cache mit nicht übertragenen Änderungen.
     */
    suspend fun logout() {
        withContext(Dispatchers.IO) { sync.removeAccount() }
        // Im lokalen Modus sind das die einzigen Daten: Sie bleiben erhalten, bis der lokale Modus wieder startet
        if (!isLocal) offlineStore.deleteCurrent()
        sessionStore.clear()
    }
}
