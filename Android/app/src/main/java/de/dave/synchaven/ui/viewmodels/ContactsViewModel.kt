package de.dave.synchaven.ui.viewmodels

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.ContactRepository
import de.dave.synchaven.data.FileTransfer
import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.AddressBookInput
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.userMessage
import de.dave.synchaven.pluralText
import de.dave.synchaven.ui.common.UiMessages
import de.dave.synchaven.uiText
import de.dave.synchaven.util.LOCALE
import de.dave.synchaven.util.displayName
import de.dave.synchaven.util.groupLetter
import de.dave.synchaven.util.matches
import java.text.Collator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ContactGroup(val letter: String, val contacts: List<Contact>)

data class ContactsUiState(
    val addressBooks: List<AddressBook> = emptyList(),
    /** `null` = alle Adressbücher */
    val selectedBookId: String? = null,
    val query: String = "",
    val groups: List<ContactGroup> = emptyList(),
    val total: Int = 0,
    /** Erstes Laden (noch keine Kontakte) */
    val loading: Boolean = true,
    /** Neu laden bei vorhandenen Kontakten */
    val refreshing: Boolean = false,
    val error: UiText? = null,
) {
    val selectedBook: AddressBook? get() = addressBooks.firstOrNull { it.id == selectedBookId }
}

class ContactsViewModel(
    private val repo: ContactRepository,
    private val files: FileTransfer,
) : ViewModel() {
    val messages = UiMessages()
    private val selectedBookId = MutableStateFlow<String?>(null)
    private val query = MutableStateFlow("")
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<UiText?>(null)
    private val collator = Collator.getInstance(LOCALE).apply { strength = Collator.PRIMARY }

    val state: StateFlow<ContactsUiState> = combine(
        repo.addressBooks, repo.contacts, selectedBookId, query, combine(loading, error, ::Pair),
    ) { books, contacts, bookId, q, (isLoading, err) ->
        val validBook = bookId?.takeIf { id -> books.orEmpty().any { it.id == id } }
        val filtered = contacts.orEmpty()
            .filter { validBook == null || it.addressBookId == validBook }
            .filter { it.matches(q) }
            .sortedWith { a, b -> collator.compare(a.displayName(), b.displayName()) }
        val groups = filtered.groupBy { it.groupLetter() }
            .map { (letter, list) -> ContactGroup(letter, list) }
            .sortedWith(compareBy({ it.letter == "#" }, { it.letter }))
        ContactsUiState(
            addressBooks = books.orEmpty(), selectedBookId = validBook, query = q,
            groups = groups, total = filtered.size,
            loading = isLoading && contacts == null, refreshing = isLoading && contacts != null, error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ContactsUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            runCatching { repo.loadAll() }
                .onSuccess { error.value = null }
                .onFailure { error.value = it.userMessage() }
            loading.value = false
        }
    }

    fun onQueryChange(q: String) {
        query.value = q
    }

    fun selectBook(id: String?) {
        selectedBookId.value = id
    }

    fun saveAddressBook(id: String?, input: AddressBookInput) = launchAction {
        if (id == null) repo.createAddressBook(input) else repo.updateAddressBook(id, input)
        messages.send(uiText(if (id == null) R.string.msg_book_created else R.string.msg_book_saved))
    }

    fun deleteAddressBook(book: AddressBook) = launchAction {
        repo.deleteAddressBook(book.id)
        messages.send(uiText(R.string.msg_book_deleted, book.name))
    }

    fun exportAddressBook(book: AddressBook, uri: Uri) = launchAction {
        files.writeText(uri, repo.exportAddressBook(book.id))
        messages.send(pluralText(R.plurals.msg_contacts_exported, book.contactCount))
    }

    fun importAddressBook(book: AddressBook, uri: Uri) = launchAction {
        val r = repo.importAddressBook(book.id, files.readText(uri))
        val parts = buildList {
            add(pluralText(R.plurals.import_created, r.created))
            add(pluralText(R.plurals.import_updated, r.updated))
            if (r.unchanged > 0) add(pluralText(R.plurals.import_unchanged, r.unchanged))
            if (r.skipped.isNotEmpty()) add(pluralText(R.plurals.import_skipped, r.skipped.size))
        }
        messages.send(pluralText(R.plurals.msg_contacts_imported, r.imported, r.imported, UiText.Join(parts)))
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch { runCatching { block() }.onFailure { messages.send(it.userMessage()) } }
    }
}
