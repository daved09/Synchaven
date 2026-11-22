package de.dave.synchaven.ui.contacts

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import de.dave.synchaven.UiText
import de.dave.synchaven.data.ContactRepository
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.userMessage
import de.dave.synchaven.ui.common.UiMessages
import de.dave.synchaven.ui.navigation.ContactDetailRoute
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ContactDetailUiState(
    val loading: Boolean = true,
    val contact: Contact? = null,
    val photo: Bitmap? = null,
    val addressBookName: String = "",
    val error: UiText? = null,
    /** Kontakt gelöscht → Bildschirm schließen */
    val deleted: Boolean = false,
)

class ContactDetailViewModel(
    private val repo: ContactRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val id = savedStateHandle.toRoute<ContactDetailRoute>().id
    private val _state = MutableStateFlow(ContactDetailUiState())
    val state = _state.asStateFlow()
    val messages = UiMessages()

    init {
        load()
        // Nach dem Bearbeiten (Liste im Repository geändert) neu laden
        viewModelScope.launch { repo.contacts.drop(1).collect { if (!_state.value.deleted) load(quiet = true) } }
    }

    fun load(quiet: Boolean = false) {
        viewModelScope.launch {
            if (!quiet) _state.update { it.copy(loading = true, error = null) }
            runCatching {
                val contact = repo.contact(id)
                val books = repo.ensureAddressBooks()
                val photo = contact.photo?.let { decodePhoto(it) }
                Triple(contact, photo, books.firstOrNull { it.id == contact.addressBookId }?.name.orEmpty())
            }.onSuccess { (contact, photo, book) ->
                _state.update { it.copy(loading = false, contact = contact, photo = photo, addressBookName = book) }
            }.onFailure { e ->
                if (quiet) messages.send(e.userMessage())
                else _state.update { it.copy(loading = false, error = e.userMessage()) }
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { repo.deleteContact(id) }
                .onSuccess { _state.update { it.copy(deleted = true) } }
                .onFailure { messages.send(it.userMessage()) }
        }
    }

    /** `data:image/jpeg;base64,…` → Bitmap */
    private suspend fun decodePhoto(dataUrl: String): Bitmap? = withContext(Dispatchers.Default) {
        runCatching {
            val base64 = dataUrl.substringAfter("base64,", "")
            val bytes = Base64.decode(base64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
    }
}
