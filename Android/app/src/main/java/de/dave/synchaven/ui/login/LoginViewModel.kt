package de.dave.synchaven.ui.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dave.synchaven.UiText
import de.dave.synchaven.data.AuthRepository
import de.dave.synchaven.data.model.ServerInfo
import de.dave.synchaven.data.store.SessionStore
import de.dave.synchaven.data.userMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val server: String = "",
    val email: String = "",
    val password: String = "",
    val name: String = "",
    val register: Boolean = false,
    /** Antwort von `/api/info` für den eingegebenen Server, `null` solange unbekannt */
    val serverInfo: ServerInfo? = null,
    val serverError: UiText? = null,
    val checkingServer: Boolean = false,
    val loading: Boolean = false,
    val error: UiText? = null,
) {
    val canSubmit: Boolean
        get() = !loading && server.isNotBlank() && email.isNotBlank() &&
            password.length >= (if (register) 8 else 1)
}

class LoginViewModel(
    private val auth: AuthRepository,
    sessionStore: SessionStore,
) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state = _state.asStateFlow()
    private var checkJob: Job? = null

    init {
        viewModelScope.launch {
            val (server, email) = sessionStore.lastLogin.first()
            _state.update { if (it.server.isEmpty()) it.copy(server = server, email = email) else it }
            if (server.isNotBlank()) checkServer(immediately = true)
        }
    }

    fun onServerChange(v: String) {
        _state.update { it.copy(server = v, error = null, serverInfo = null, serverError = null) }
        checkServer()
    }

    fun onEmailChange(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null) }
    fun onNameChange(v: String) = _state.update { it.copy(name = v, error = null) }
    fun onRegisterChange(v: Boolean) = _state.update { it.copy(register = v, error = null) }

    /** Fragt `/api/info` ab, sobald die Eingabe kurz ruht – zeigt Servername und ob Registrierung möglich ist. */
    fun checkServer(immediately: Boolean = false) {
        checkJob?.cancel()
        val server = _state.value.server
        if (server.isBlank()) return
        checkJob = viewModelScope.launch {
            if (!immediately) delay(700)
            _state.update { it.copy(checkingServer = true) }
            runCatching { auth.serverInfo(server) }
                .onSuccess { info ->
                    _state.update {
                        it.copy(
                            serverInfo = info, serverError = null, checkingServer = false,
                            register = it.register && info.allowRegistration,
                        )
                    }
                }
                .onFailure { e -> _state.update { it.copy(serverError = e.userMessage(), checkingServer = false) } }
        }
    }

    /** Ohne Server weiter; der Sitzungszustand wechselt und die Navigation reagiert darauf. */
    fun useLocally() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { auth.startLocal() }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
        }
    }

    /** Bei Erfolg wechselt der Sitzungszustand und die Navigation reagiert darauf. */
    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                if (s.register) auth.register(s.server, s.email, s.password, s.name)
                else auth.login(s.server, s.email, s.password)
            }.onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
        }
    }
}
