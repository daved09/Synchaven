package de.dave.synchaven.data

import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.api.apiCall
import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.AddressBookInput
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.ContactImportResult
import de.dave.synchaven.data.model.ContactInput
import de.dave.synchaven.data.offline.OfflineStore
import de.dave.synchaven.data.offline.OfflineSync
import de.dave.synchaven.data.offline.PendingOp
import de.dave.synchaven.data.offline.isLocalId
import de.dave.synchaven.data.offline.newLocalId
import de.dave.synchaven.data.offline.toLocalContact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Address books and contacts. The contact list (without photos) is kept in the [OfflineStore]; photos are cached
 * when a contact is opened. Without a connection, changes go into the queue of [OfflineSync].
 * [onContactsChanged] is called after every contact change on the server, because the server derives the
 * birthday calendar from it.
 */
class ContactRepository(
    private val api: SyncHavenApi,
    private val store: OfflineStore,
    private val sync: OfflineSync,
    scope: CoroutineScope,
    private val onContactsChanged: () -> Unit = {},
) {
    val addressBooks: StateFlow<List<AddressBook>?> =
        store.data.map { it.addressBooks }.stateIn(scope, SharingStarted.Eagerly, null)

    val contacts: StateFlow<List<Contact>?> =
        store.data.map { it.contacts }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Loads address books and contacts; without a connection the cache is enough. */
    suspend fun loadAll() {
        sync.read(cached = { d -> Unit.takeIf { d.addressBooks != null && d.contacts != null } }) {
            val books = apiCall { api.addressBooks() }
            val list = apiCall { api.contacts() }
            sync.mergeServer { it.copy(addressBooks = books, contacts = list) }
        }
        sync.syncIfNeeded()
    }

    suspend fun ensureAddressBooks(): List<AddressBook> {
        store.awaitReady()
        return store.data.value.addressBooks ?: sync.read(cached = { it.addressBooks }) {
            val books = apiCall { api.addressBooks() }
            sync.mergeServer { it.copy(addressBooks = books) }
            store.data.value.addressBooks ?: books
        }
    }

    private suspend fun reloadAddressBooks() {
        val books = apiCall { api.addressBooks() }
        sync.mergeServer { it.copy(addressBooks = books) }
    }

    /** Contact with photo – from the server or (without a connection or with pending changes) from the cache. */
    suspend fun contact(id: String): Contact {
        store.awaitReady()
        val rid = store.data.value.resolve(id)
        val c = sync.read(
            preferCache = isLocalId(rid) || sync.hasPending(rid),
            cached = { d -> d.contacts?.firstOrNull { it.id == rid } },
        ) {
            apiCall { api.contact(rid) }.also { c ->
                if (c.photo != null || !c.hasPhoto) store.savePhoto(c.id, c.etag, c.photo)
                patchContact(c)
            }
        }
        return if (c.photo != null) c else c.copy(photo = cachedPhoto(c))
    }

    /** Photo from a change not yet transferred, otherwise the cached one for the current state */
    private suspend fun cachedPhoto(c: Contact): String? {
        val pending = store.data.value.outbox.lastOrNull { op ->
            (op is PendingOp.CreateContact && op.localId == c.id && op.input.photo != null) ||
                (op is PendingOp.UpdateContact && op.id == c.id && op.input.photo != null)
        }
        val pendingPhoto = when (pending) {
            is PendingOp.CreateContact -> pending.input.photo
            is PendingOp.UpdateContact -> pending.input.photo
            else -> null
        }
        if (pendingPhoto != null) return pendingPhoto.ifEmpty { null }
        return if (c.hasPhoto) store.photo(c.id, c.etag) else null
    }

    suspend fun createContact(input: ContactInput): Contact {
        val localId = newLocalId()
        return sync.write(
            PendingOp.CreateContact(localId, input),
            online = {
                apiCall { api.createContact(input) }.also { c ->
                    store.savePhoto(c.id, c.etag, c.photo ?: input.photo)
                    patchContact(c)
                    onContactsChanged()
                }
            },
            offline = { d -> d.contacts?.firstOrNull { it.id == localId } ?: input.toLocalContact(localId) },
        )
    }

    suspend fun updateContact(id: String, input: ContactInput): Contact = sync.write(
        PendingOp.UpdateContact(id, input),
        online = {
            apiCall { api.updateContact(id, input) }.also { c ->
                if (c.photo != null || input.photo != null) store.savePhoto(c.id, c.etag, c.photo ?: input.photo)
                patchContact(c)
                onContactsChanged()
            }
        },
        offline = { d -> d.contacts?.firstOrNull { it.id == d.resolve(id) } ?: input.toLocalContact(d.resolve(id)) },
    )

    suspend fun deleteContact(id: String) {
        sync.write(
            PendingOp.DeleteContact(id),
            online = {
                apiCall { api.deleteContact(id) }
                store.savePhoto(id, "", null)
                sync.mergeServer { d -> d.copy(contacts = d.contacts?.filterNot { it.id == id }) }
                onContactsChanged()
            },
            offline = { },
        )
    }

    // ---------- Address books (only with a connection to the server) ----------

    suspend fun createAddressBook(input: AddressBookInput): AddressBook =
        sync.online { apiCall { api.createAddressBook(input) } }.also { runCatching { reloadAddressBooks() } }

    suspend fun updateAddressBook(id: String, input: AddressBookInput): AddressBook =
        sync.online { apiCall { api.updateAddressBook(id, input) } }.also { runCatching { reloadAddressBooks() } }

    suspend fun deleteAddressBook(id: String) {
        sync.online { apiCall { api.deleteAddressBook(id) } }
        sync.mergeServer { d ->
            d.copy(addressBooks = d.addressBooks?.filterNot { it.id == id }, contacts = d.contacts?.filterNot { it.addressBookId == id })
        }
        onContactsChanged()
        runCatching { loadAll() }
    }

    suspend fun exportAddressBook(id: String): String = sync.online { apiCall { api.exportAddressBook(id).string() } }

    suspend fun importAddressBook(id: String, vcf: String): ContactImportResult =
        sync.online { apiCall { api.importAddressBook(id, vcf.toRequestBody("text/vcard; charset=utf-8".toMediaType())) } }
            .also { onContactsChanged(); runCatching { loadAll() } }

    /** Takes a contact returned by the server (without photo) into the cache. */
    private suspend fun patchContact(c: Contact) {
        val plain = c.copy(photo = null)
        sync.mergeServer { d ->
            d.copy(contacts = d.contacts?.let { list ->
                if (list.any { it.id == c.id }) list.map { if (it.id == c.id) plain else it } else list + plain
            })
        }
    }
}
