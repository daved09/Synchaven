package de.dave.synchaven.data

import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.api.apiCall
import de.dave.synchaven.data.api.normalizeServerUrl
import de.dave.synchaven.data.model.LoginRequest
import de.dave.synchaven.data.model.RegisterRequest
import de.dave.synchaven.data.model.ServerInfo
import de.dave.synchaven.data.model.UpdateMeRequest
import de.dave.synchaven.data.model.User
import de.dave.synchaven.data.store.SessionStore

/** Anmeldung, Registrierung und Benutzerkonto. */
class AuthRepository(
    private val api: SyncHavenApi,
    private val sessionStore: SessionStore,
) {
    suspend fun serverInfo(server: String): ServerInfo = apiCall { api.info(normalizeServerUrl(server)) }

    suspend fun currentServerInfo(): ServerInfo = apiCall { api.currentInfo() }

    /** Meldet an und speichert die Sitzung; die Navigation reagiert auf den neuen Sitzungszustand. */
    suspend fun login(server: String, email: String, password: String) {
        val url = normalizeServerUrl(server)
        val res = apiCall { api.login(url, LoginRequest(email.trim(), password)) }
        sessionStore.save(url, res.token, res.user)
    }

    suspend fun register(server: String, email: String, password: String, name: String) {
        val url = normalizeServerUrl(server)
        val res = apiCall { api.register(url, RegisterRequest(email.trim(), password, name.trim())) }
        sessionStore.save(url, res.token, res.user)
    }

    suspend fun me(): User = apiCall { api.me() }.also { sessionStore.updateUser(it) }

    suspend fun updateMe(request: UpdateMeRequest): User =
        apiCall { api.updateMe(request) }.also { sessionStore.updateUser(it) }

    suspend fun logout() = sessionStore.clear()
}
