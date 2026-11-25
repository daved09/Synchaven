package de.dave.synchaven.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dave.synchaven.UiText
import de.dave.synchaven.data.AuthRepository
import de.dave.synchaven.data.TOTP_INVALID
import de.dave.synchaven.data.TOTP_REQUIRED
import de.dave.synchaven.data.api.ApiException
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
    /** The account has two-factor authentication: the sign-in also needs [code] */
    val needsCode: Boolean = false,
    /** Code of the authenticator app or a recovery code */
    val code: String = "",
    /** Response of `/api/info` for the entered server, `null` as long as unknown */
    val serverInfo: ServerInfo? = null,
    val serverError: UiText? = null,
    val checkingServer: Boolean = false,
    val loading: Boolean = false,
    val error: UiText? = null,
) {
    val canSubmit: Boolean
        get() = !loading && server.isNotBlank() && email.isNotBlank() &&
            password.length >= (if (register) 8 else 1) && (!needsCode || code.isNotBlank())
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

    // A different account or password: the code step starts over
    fun onEmailChange(v: String) = _state.update { it.copy(email = v, error = null, needsCode = false, code = "") }
    fun onPasswordChange(v: String) = _state.update { it.copy(password = v, error = null, needsCode = false, code = "") }
    fun onCodeChange(v: String) = _state.update { it.copy(code = v, error = null) }
    fun onNameChange(v: String) = _state.update { it.copy(name = v, error = null) }
    fun onRegisterChange(v: Boolean) = _state.update { it.copy(register = v, error = null, needsCode = false, code = "") }

    /** Queries `/api/info` as soon as the input pauses briefly – shows the server name and whether registration is possible. */
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

    /** Continue without a server; the session state changes and the navigation reacts to it. */
    fun useLocally() {
        if (_state.value.loading) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching { auth.startLocal() }
                .onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
        }
    }

    /** On success the session state changes and the navigation reacts to it. */
    fun submit() {
        val s = _state.value
        if (!s.canSubmit) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            runCatching {
                if (s.register) auth.register(s.server, s.email, s.password, s.name)
                else auth.login(s.server, s.email, s.password, s.code.takeIf { s.needsCode })
            }.onFailure { e ->
                val code = (e as? ApiException)?.code
                _state.update {
                    when (code) {
                        // Correct password: now ask for the code, no error message
                        TOTP_REQUIRED -> it.copy(loading = false, needsCode = true, code = "")
                        TOTP_INVALID -> it.copy(loading = false, code = "", error = e.userMessage())
                        else -> it.copy(loading = false, error = e.userMessage())
                    }
                }
            }
        }
    }
}
