package de.dave.synchaven.data

import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.api.apiCall
import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.AddressBookInput
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.ContactImportResult
import de.dave.synchaven.data.model.ContactInput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Adressbücher und Kontakte. Die Kontaktliste (ohne Fotos) wird zwischengespeichert, damit Liste,
 * Detail- und Bearbeitungsansicht nach Änderungen sofort übereinstimmen.
 * [onContactsChanged] wird nach jeder Kontaktänderung aufgerufen, weil der Server daraus den
 * Geburtstagskalender ableitet.
 */
class ContactRepository(
    private val api: SyncHavenApi,
    private val onContactsChanged: () -> Unit = {},
) {
    private val _addressBooks = MutableStateFlow<List<AddressBook>?>(null)
    val addressBooks: StateFlow<List<AddressBook>?> = _addressBooks.asStateFlow()

    private val _contacts = MutableStateFlow<List<Contact>?>(null)
    val contacts: StateFlow<List<Contact>?> = _contacts.asStateFlow()

    suspend fun loadAll() {
        _addressBooks.value = apiCall { api.addressBooks() }
        _contacts.value = apiCall { api.contacts() }
    }

    suspend fun ensureAddressBooks(): List<AddressBook> =
        _addressBooks.value ?: apiCall { api.addressBooks() }.also { _addressBooks.value = it }

    private suspend fun reloadAddressBooks() {
        _addressBooks.value = apiCall { api.addressBooks() }
    }

    suspend fun contact(id: String): Contact = apiCall { api.contact(id) }

    suspend fun createContact(input: ContactInput): Contact {
        val c = apiCall { api.createContact(input) }
        _contacts.update { (it ?: emptyList()) + c.copy(photo = null) }
        onContactsChanged()
        reloadAddressBooks()
        return c
    }

    suspend fun updateContact(id: String, input: ContactInput): Contact {
        val c = apiCall { api.updateContact(id, input) }
        _contacts.update { list -> list?.map { if (it.id == id) c.copy(photo = null) else it } }
        onContactsChanged()
        reloadAddressBooks()
        return c
    }

    suspend fun deleteContact(id: String) {
        apiCall { api.deleteContact(id) }
        _contacts.update { list -> list?.filterNot { it.id == id } }
        onContactsChanged()
        reloadAddressBooks()
    }

    suspend fun createAddressBook(input: AddressBookInput): AddressBook =
        apiCall { api.createAddressBook(input) }.also { reloadAddressBooks() }

    suspend fun updateAddressBook(id: String, input: AddressBookInput): AddressBook =
        apiCall { api.updateAddressBook(id, input) }.also { reloadAddressBooks() }

    suspend fun deleteAddressBook(id: String) {
        apiCall { api.deleteAddressBook(id) }
        onContactsChanged()
        loadAll()
    }

    suspend fun exportAddressBook(id: String): String = apiCall { api.exportAddressBook(id).string() }

    suspend fun importAddressBook(id: String, vcf: String): ContactImportResult =
        apiCall { api.importAddressBook(id, vcf.toRequestBody("text/vcard; charset=utf-8".toMediaType())) }
            .also { onContactsChanged(); loadAll() }

    fun reset() {
        _addressBooks.value = null
        _contacts.value = null
    }
}
