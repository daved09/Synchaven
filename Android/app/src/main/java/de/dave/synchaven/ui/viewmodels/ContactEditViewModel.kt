package de.dave.synchaven.ui.viewmodels

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.ContactRepository
import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.PostalAddress
import de.dave.synchaven.data.model.TypedValue
import de.dave.synchaven.data.userMessage
import de.dave.synchaven.ui.navigation.ContactEditRoute
import de.dave.synchaven.uiText
import de.dave.synchaven.util.birthdayFromInput
import de.dave.synchaven.util.birthdayToInput
import de.dave.synchaven.util.derivedName
import de.dave.synchaven.util.isBlank
import de.dave.synchaven.util.partsName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Welche Liste eines Kontakts bearbeitet wird */
enum class TypedField { PHONES, EMAILS, URLS }

data class ContactEditUiState(
    val loading: Boolean = true,
    val isNew: Boolean = true,
    val addressBooks: List<AddressBook> = emptyList(),
    /**
     * `fullName` bleibt nur bei Kontakten ohne Namensteile erhalten (z. B. importiert mit nur FN), sonst leer:
     * der Anzeigename wird immer aus den Namensteilen gebildet.
     */
    val contact: Contact = Contact(),
    /** Geburtstag wie eingegeben (TT.MM.JJJJ oder TT.MM.) */
    val birthdayInput: String = "",
    /** Präfix, zweiter Vorname, Suffix und Spitzname anzeigen */
    val showAllNameFields: Boolean = false,
    val saving: Boolean = false,
    val error: UiText? = null,
    /** Gespeichert → Bildschirm schließen; enthält die ID des Kontakts */
    val savedId: String? = null,
) {
    val birthdayValid: Boolean get() = birthdayFromInput(birthdayInput) != null
}

class ContactEditViewModel(
    private val repo: ContactRepository,
    savedStateHandle: SavedStateHandle,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<ContactEditRoute>()
    private val _state = MutableStateFlow(ContactEditUiState(isNew = route.id == null))
    val state = _state.asStateFlow()

    init {
        viewModelScope.launch {
            runCatching {
                val books = repo.ensureAddressBooks()
                val contact = route.id?.let { repo.contact(it) }
                    ?: Contact(
                        addressBookId = route.addressBookId?.takeIf { id -> books.any { it.id == id } }
                            ?: books.firstOrNull()?.id.orEmpty(),
                        phones = listOf(TypedValue("mobile", "")),
                        emails = listOf(TypedValue("home", "")),
                    )
                books to contact
            }.onSuccess { (books, contact) ->
                _state.update {
                    it.copy(
                        loading = false, addressBooks = books,
                        contact = contact.copy(photo = null, fullName = if (contact.partsName().isEmpty()) contact.fullName else ""),
                        birthdayInput = birthdayToInput(contact.birthday),
                        showAllNameFields = listOf(contact.prefix, contact.additionalNames, contact.suffix, contact.nickname)
                            .any { s -> s.isNotBlank() },
                    )
                }
            }.onFailure { e -> _state.update { it.copy(loading = false, error = e.userMessage()) } }
        }
    }

    fun update(transform: (Contact) -> Contact) = _state.update { it.copy(contact = transform(it.contact), error = null) }

    fun onBirthdayChange(v: String) = _state.update { it.copy(birthdayInput = v, error = null) }

    fun showAllNameFields() = _state.update { it.copy(showAllNameFields = true) }

    // ---------- Telefon, E-Mail, Websites ----------

    private fun Contact.list(field: TypedField) = when (field) {
        TypedField.PHONES -> phones
        TypedField.EMAILS -> emails
        TypedField.URLS -> urls
    }

    private fun Contact.withList(field: TypedField, list: List<TypedValue>) = when (field) {
        TypedField.PHONES -> copy(phones = list)
        TypedField.EMAILS -> copy(emails = list)
        TypedField.URLS -> copy(urls = list)
    }

    fun addTyped(field: TypedField) = update {
        val type = when (field) {
            TypedField.PHONES -> if (it.phones.isEmpty()) "mobile" else "home"
            TypedField.EMAILS -> "home"
            TypedField.URLS -> "home"
        }
        it.withList(field, it.list(field) + TypedValue(type, ""))
    }

    fun updateTyped(field: TypedField, index: Int, value: TypedValue) = update {
        it.withList(field, it.list(field).mapIndexed { i, v -> if (i == index) value else v })
    }

    fun removeTyped(field: TypedField, index: Int) = update {
        it.withList(field, it.list(field).filterIndexed { i, _ -> i != index })
    }

    // ---------- Adressen ----------

    fun addAddress() = update { it.copy(addresses = it.addresses + PostalAddress(type = "home")) }

    fun updateAddress(index: Int, value: PostalAddress) = update {
        it.copy(addresses = it.addresses.mapIndexed { i, a -> if (i == index) value else a })
    }

    fun removeAddress(index: Int) = update { it.copy(addresses = it.addresses.filterIndexed { i, _ -> i != index }) }

    fun save() {
        val s = _state.value
        val birthday = birthdayFromInput(s.birthdayInput)
        val c = s.contact.copy(
            phones = s.contact.phones.filter { it.value.isNotBlank() }.map { it.copy(value = it.value.trim()) },
            emails = s.contact.emails.filter { it.value.isNotBlank() }.map { it.copy(value = it.value.trim()) },
            urls = s.contact.urls.filter { it.value.isNotBlank() }.map { it.copy(value = it.value.trim()) },
            addresses = s.contact.addresses.filterNot { it.isBlank() },
            birthday = birthday.orEmpty(),
        )
        val error = when {
            birthday == null -> uiText(R.string.error_birthday)
            c.addressBookId.isEmpty() -> uiText(R.string.error_address_book_required)
            c.fullName.isBlank() && c.derivedName().isBlank() -> uiText(R.string.error_contact_empty)
            else -> null
        }
        if (error != null) {
            _state.update { it.copy(error = error) }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            runCatching {
                val id = route.id
                if (id == null) repo.createContact(c.toInput()) else repo.updateContact(id, c.toInput())
            }
                .onSuccess { saved -> _state.update { it.copy(saving = false, savedId = saved.id) } }
                .onFailure { e -> _state.update { it.copy(saving = false, error = e.userMessage()) } }
        }
    }
}
