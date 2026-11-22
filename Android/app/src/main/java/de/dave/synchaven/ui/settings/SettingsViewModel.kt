package de.dave.synchaven.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.AuthRepository
import de.dave.synchaven.data.model.ServerInfo
import de.dave.synchaven.data.model.UpdateMeRequest
import de.dave.synchaven.data.store.AppSettings
import de.dave.synchaven.data.store.Session
import de.dave.synchaven.data.store.SessionState
import de.dave.synchaven.data.store.SessionStore
import de.dave.synchaven.data.store.SettingsStore
import de.dave.synchaven.data.userMessage
import de.dave.synchaven.ui.common.UiMessages
import de.dave.synchaven.uiText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Zustand des Dialogs „Profil bearbeiten“ */
data class ProfileForm(
    val name: String = "",
    val email: String = "",
    val currentPassword: String = "",
    val newPassword: String = "",
    val saving: Boolean = false,
    val error: UiText? = null,
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val sessionStore: SessionStore,
    private val auth: AuthRepository,
) : ViewModel() {
    val messages = UiMessages()

    val settings: StateFlow<AppSettings> = settingsStore.settings

    val session: StateFlow<Session?> = sessionStore.state
        .map { (it as? SessionState.LoggedIn)?.session }
        .stateIn(viewModelScope, SharingStarted.Eagerly, sessionStore.current)

    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)
    val serverInfo = _serverInfo.asStateFlow()

    /** `null` = Dialog geschlossen */
    private val _profile = MutableStateFlow<ProfileForm?>(null)
    val profile = _profile.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching { auth.currentServerInfo() }.onSuccess { _serverInfo.value = it }
            // Name/E-Mail könnten in der Weboberfläche geändert worden sein
            runCatching { auth.me() }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        viewModelScope.launch { settingsStore.update(transform) }
    }

    fun openProfile() {
        val s = session.value ?: return
        _profile.value = ProfileForm(name = s.name, email = s.email)
    }

    fun closeProfile() {
        _profile.value = null
    }

    fun editProfile(transform: (ProfileForm) -> ProfileForm) = _profile.update { it?.let(transform)?.copy(error = null) }

    fun saveProfile() {
        val form = _profile.value ?: return
        val current = session.value ?: return
        val emailChanged = form.email.trim() != current.email
        val error = when {
            form.email.isBlank() -> uiText(R.string.error_email_required)
            form.newPassword.isNotEmpty() && form.newPassword.length < 8 -> uiText(R.string.error_password_too_short)
            (emailChanged || form.newPassword.isNotEmpty()) && form.currentPassword.isEmpty() ->
                uiText(R.string.error_current_password_required)
            else -> null
        }
        if (error != null) {
            _profile.update { it?.copy(error = error) }
            return
        }
        _profile.update { it?.copy(saving = true) }
        viewModelScope.launch {
            runCatching {
                auth.updateMe(
                    UpdateMeRequest(
                        name = form.name.trim(),
                        email = form.email.trim().takeIf { emailChanged },
                        currentPassword = form.currentPassword.ifEmpty { null },
                        newPassword = form.newPassword.ifEmpty { null },
                    )
                )
            }.onSuccess {
                _profile.value = null
                messages.send(uiText(R.string.msg_profile_saved))
            }.onFailure { e -> _profile.update { it?.copy(saving = false, error = e.userMessage()) } }
        }
    }

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }
}
