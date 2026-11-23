package de.dave.synchaven.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.AuthRepository
import de.dave.synchaven.data.model.ServerInfo
import de.dave.synchaven.data.offline.OfflineStatus
import de.dave.synchaven.data.offline.OfflineSync
import de.dave.synchaven.data.model.UpdateMeRequest
import de.dave.synchaven.data.store.AppSettings
import de.dave.synchaven.data.store.Session
import de.dave.synchaven.data.store.SessionState
import de.dave.synchaven.data.store.SessionStore
import de.dave.synchaven.data.store.SettingsStore
import de.dave.synchaven.data.store.ViewMode
import de.dave.synchaven.data.userMessage
import de.dave.synchaven.sync.SyncController
import de.dave.synchaven.sync.SyncKind
import de.dave.synchaven.sync.SyncState
import de.dave.synchaven.ui.common.UiMessages
import de.dave.synchaven.uiText
import kotlinx.coroutines.Dispatchers
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

/** Zustand des Dialogs „Synchronisierung einrichten“ (Passwort für das Android-Konto) */
data class SyncSetupForm(
    val password: String = "",
    val saving: Boolean = false,
    val error: UiText? = null,
)

class SettingsViewModel(
    private val settingsStore: SettingsStore,
    private val sessionStore: SessionStore,
    private val auth: AuthRepository,
    private val syncController: SyncController,
    private val offlineSync: OfflineSync,
) : ViewModel() {
    val messages = UiMessages()

    /** Stand des Offline-Caches */
    val offline: StateFlow<OfflineStatus> = offlineSync.status

    fun syncOfflineNow() = offlineSync.requestSync()

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

    /** Die Standardansicht gehört zum Benutzerkonto */
    fun setDefaultView(mode: ViewMode) {
        viewModelScope.launch {
            runCatching { auth.setDefaultView(mode) }.onFailure { messages.send(it.userMessage()) }
        }
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

    // ---------- Synchronisierung mit Android ----------

    val sync: StateFlow<SyncState> = syncController.state

    /** `null` = Dialog geschlossen */
    private val _syncSetup = MutableStateFlow<SyncSetupForm?>(null)
    val syncSetup = _syncSetup.asStateFlow()

    /** Konto entfernt (Android-Einstellungen) oder Berechtigung entzogen? */
    fun refreshSync() = syncController.refresh()

    /** Ohne Konto: aus der Sitzung anlegen. Mit Konto (Anmeldung fehlgeschlagen): Passwort abfragen. */
    fun openSyncSetup() {
        if (sync.value.accountName != null) {
            _syncSetup.value = SyncSetupForm()
            return
        }
        viewModelScope.launch {
            runCatching { auth.ensureSyncAccount(force = true) }
                .onFailure { messages.send(it.userMessage()) }
        }
    }

    fun closeSyncSetup() {
        _syncSetup.value = null
    }

    fun editSyncPassword(password: String) = _syncSetup.update { it?.copy(password = password, error = null) }

    fun submitSyncSetup() {
        val form = _syncSetup.value ?: return
        if (form.password.isEmpty()) return
        _syncSetup.update { it?.copy(saving = true) }
        viewModelScope.launch {
            runCatching { auth.setupSync(form.password) }
                .onSuccess {
                    _syncSetup.value = null
                    // War die Synchronisierung schon eingeschaltet (Passwort erneuert), gleich nachholen
                    syncController.requestSync(manual = true)
                    messages.send(uiText(R.string.msg_sync_set_up))
                }
                .onFailure { e -> _syncSetup.update { it?.copy(saving = false, error = e.userMessage()) } }
        }
    }

    /** Die Berechtigungen hat der Bildschirm bereits eingeholt. */
    fun enableSync(kind: SyncKind) {
        viewModelScope.launch(Dispatchers.IO) { syncController.enable(kind) }
    }

    fun disableSync(kind: SyncKind) {
        viewModelScope.launch { syncController.disable(kind) }
    }

    fun syncPermissionDenied() = messages.send(uiText(R.string.sync_permission_denied))

    fun setSyncInterval(minutes: Int) {
        viewModelScope.launch(Dispatchers.IO) { syncController.setInterval(minutes) }
    }

    fun syncNow() = syncController.requestSync(manual = true)

    fun logout() {
        viewModelScope.launch { auth.logout() }
    }
}
