package de.dave.synchaven.sync

import android.accounts.Account
import android.content.ContentProviderClient
import android.content.ContentProviderOperation
import android.content.ContentUris
import android.content.ContentValues
import android.content.SyncResult
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Event
import android.provider.ContactsContract.CommonDataKinds.GroupMembership
import android.provider.ContactsContract.CommonDataKinds.Nickname
import android.provider.ContactsContract.CommonDataKinds.Note
import android.provider.ContactsContract.CommonDataKinds.Organization
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.provider.ContactsContract.CommonDataKinds.Photo
import android.provider.ContactsContract.CommonDataKinds.StructuredName
import android.provider.ContactsContract.CommonDataKinds.StructuredPostal
import android.provider.ContactsContract.CommonDataKinds.Website
import android.provider.ContactsContract.Data
import android.provider.ContactsContract.Groups
import android.provider.ContactsContract.RawContacts
import android.util.Base64
import android.util.Log
import androidx.core.graphics.scale
import de.dave.synchaven.data.api.ApiException
import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.api.apiCall
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.ContactInput
import de.dave.synchaven.data.model.PostalAddress
import de.dave.synchaven.data.model.TypedValue
import de.dave.synchaven.util.cleanCategories
import java.io.ByteArrayOutputStream

private const val TAG = "ContactsSync"

/** Größere Fotos werden vor dem Einfügen verkleinert (Binder-Transaktionen sind auf ~1 MB begrenzt). */
private const val MAX_PHOTO_BYTES = 400_000
private const val MAX_PHOTO_SIZE = 720

/**
 * Eigene, unsichtbare Datenart: Bei Kontakten mit Spitzname steht als Name nur der Spitzname auf dem Gerät (sonst
 * bildet Android den Anzeigenamen aus Vor- und Nachname). Diese Zeile bewahrt die echten Namensteile:
 * `DATA1` = als Name geschriebener Spitzname, `DATA2` … `DATA6` = Präfix, Vorname, weitere Vornamen, Nachname, Suffix.
 */
private const val REAL_NAME_MIMETYPE = "vnd.android.cursor.item/vnd.de.dave.synchaven.realname"

/** Datenarten, die die App verwaltet – andere bleiben beim Aktualisieren erhalten. */
private val MANAGED_MIMETYPES = listOf(
    StructuredName.CONTENT_ITEM_TYPE, REAL_NAME_MIMETYPE, Nickname.CONTENT_ITEM_TYPE, Organization.CONTENT_ITEM_TYPE,
    Phone.CONTENT_ITEM_TYPE, Email.CONTENT_ITEM_TYPE, StructuredPostal.CONTENT_ITEM_TYPE,
    Website.CONTENT_ITEM_TYPE, Event.CONTENT_ITEM_TYPE, Note.CONTENT_ITEM_TYPE, Photo.CONTENT_ITEM_TYPE,
    GroupMembership.CONTENT_ITEM_TYPE,
)

/** `SYNC4` eines Kontakts, dessen Gruppen die Kategorien des Servers wiedergeben (ab dieser App-Version geschrieben) */
private const val CATEGORIES_MAPPED = "categories"

/** `SYNC4` der aktuellen Schreibweise (Kategorien als Gruppen, Spitzname als Name); ältere werden neu geschrieben */
private const val SYNC_FORMAT = "categories,nickname"

private data class LocalGroup(val id: Long, val title: String, val dirty: Boolean, val deleted: Boolean)

private data class LocalContact(
    val id: Long,
    val serverId: String?,
    val etag: String?,
    val addressBookId: String?,
    /** `<Datenzeile>:<DATA_VERSION>` des Fotos beim letzten Abgleich, leer = kein Foto */
    val photoMarker: String?,
    val dirty: Boolean,
    val deleted: Boolean,
    /** Gruppen entsprechen den Kategorien (siehe [CATEGORIES_MAPPED]) */
    val categoriesMapped: Boolean,
    /** In der aktuellen Schreibweise ([SYNC_FORMAT]) geschrieben */
    val currentFormat: Boolean,
)

/**
 * Gleicht die Kontakte aller Adressbücher mit den Android-Kontakten (RawContacts des Kontos) ab – in beide
 * Richtungen. Lokale Änderungen werden zuerst hochgeladen, danach der Stand des Servers übernommen.
 *
 * Abbildung: `SOURCE_ID` = Kontakt-ID, `SYNC1` = ETag des Servers, `SYNC2` = Adressbuch, `SYNC3` = Stand des
 * Fotos (erkennt Fotoänderungen auf dem Gerät), `SYNC4` = [SYNC_FORMAT]. Neue Kontakte vom Gerät landen im
 * ersten Adressbuch. Kategorien sind die Gruppen (Labels) des Kontos; ungenutzte Gruppen werden entfernt.
 */
class ContactsSyncer(
    private val account: Account,
    private val provider: ContentProviderClient,
    private val api: SyncHavenApi,
    private val result: SyncResult,
    private val resources: Resources,
) {
    private fun Uri.asSyncAdapter(): Uri = buildUpon()
        .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
        .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
        .build()

    private val rawContactsUri = RawContacts.CONTENT_URI.asSyncAdapter()
    private val dataUri = Data.CONTENT_URI.asSyncAdapter()
    private val groupsUri = Groups.CONTENT_URI.asSyncAdapter()
    private fun groupUri(id: Long) = ContentUris.withAppendedId(Groups.CONTENT_URI, id).asSyncAdapter()

    /** Gruppen des Kontos nach ID; neu angelegte kommen beim Schreiben hinzu */
    private val groups = mutableMapOf<Long, LocalGroup>()
    private fun rawContactUri(id: Long) = ContentUris.withAppendedId(RawContacts.CONTENT_URI, id).asSyncAdapter()

    var firstItemError: Throwable? = null
        private set

    suspend fun sync() {
        ensureVisible()
        val books = apiCall { api.addressBooks() }
        val defaultBook = books.firstOrNull()?.id ?: return
        val bookIds = books.map { it.id }.toSet()
        loadGroups()
        applyGroupChanges()

        // 1. Lokale Änderungen hochladen
        for (c in localContacts().filter { it.dirty || it.deleted }) {
            itemSafe { upload(c, c.addressBookId?.takeIf { it in bookIds } ?: defaultBook) }
        }

        // 2. Stand des Servers übernehmen
        val remote = apiCall { api.contacts() }
        val local = localContacts()
        val localByServerId = local.filter { it.serverId != null }.associateBy { it.serverId!! }
        for (c in remote) {
            val existing = localByServerId[c.id]
            itemSafe {
                when {
                    existing == null -> {
                        write(null, full(c))
                        result.stats.numInserts++
                    }
                    existing.dirty || existing.deleted -> Unit
                    // Vor dieser App-Version geschriebene Kontakte werden einmal neu geschrieben (Kategorien als
                    // Gruppen, Spitzname als Name)
                    existing.etag != c.etag || !existing.currentFormat -> {
                        write(existing.id, full(c))
                        result.stats.numUpdates++
                    }
                }
            }
        }
        val remoteIds = remote.map { it.id }.toSet()
        val ops = Batch(provider)
        for (c in local) {
            if (c.serverId != null && c.serverId !in remoteIds && !c.dirty) {
                ops += ContentProviderOperation.newDelete(rawContactUri(c.id)).build()
                result.stats.numDeletes++
            }
        }
        ops.flush()
        deleteUnusedGroups()
    }

    // ---------- Gruppen (Kategorien) ----------

    private fun loadGroups() {
        groups.clear()
        provider.query(
            groupsUri, arrayOf(Groups._ID, Groups.TITLE, Groups.DIRTY, Groups.DELETED),
            "${Groups.ACCOUNT_NAME} = ? AND ${Groups.ACCOUNT_TYPE} = ?", arrayOf(account.name, account.type), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val g = LocalGroup(c.getLong(0), c.getString(1).orEmpty().trim(), c.getInt(2) != 0, c.getInt(3) != 0)
                groups[g.id] = g
            }
        }
    }

    private fun groupMembers(groupId: Long): List<Long> = provider.query(
        dataUri, arrayOf(Data.RAW_CONTACT_ID), "${Data.MIMETYPE} = ? AND ${GroupMembership.GROUP_ROW_ID} = ?",
        arrayOf(GroupMembership.CONTENT_ITEM_TYPE, groupId.toString()), null,
    )?.use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } } ?: emptyList()

    /**
     * Auf dem Gerät umbenannte oder gelöschte Gruppen: Die Mitglieder werden als geändert markiert, damit ihre
     * Kategorien hochgeladen werden.
     */
    private fun applyGroupChanges() {
        val ops = Batch(provider)
        for (g in groups.values.filter { it.dirty || it.deleted }) {
            for (raw in groupMembers(g.id)) {
                ops += ContentProviderOperation.newUpdate(rawContactUri(raw)).withValue(RawContacts.DIRTY, 1).build()
            }
            if (g.deleted) {
                ops += ContentProviderOperation.newDelete(dataUri).withSelection(
                    "${Data.MIMETYPE} = ? AND ${GroupMembership.GROUP_ROW_ID} = ?",
                    arrayOf(GroupMembership.CONTENT_ITEM_TYPE, g.id.toString()),
                ).build()
                ops += ContentProviderOperation.newDelete(groupUri(g.id)).build()
            } else {
                ops += ContentProviderOperation.newUpdate(groupUri(g.id)).withValue(Groups.DIRTY, 0).build()
            }
        }
        ops.flush()
        groups.values.removeAll { it.deleted }
    }

    /** ID der Gruppe zur Kategorie, bei Bedarf neu angelegt */
    private fun groupFor(category: String): Long {
        groups.values.firstOrNull { it.title.equals(category, ignoreCase = true) }?.let { return it.id }
        val values = ContentValues().apply {
            put(Groups.ACCOUNT_NAME, account.name)
            put(Groups.ACCOUNT_TYPE, account.type)
            put(Groups.TITLE, category)
            put(Groups.GROUP_VISIBLE, 1)
            put(Groups.SHOULD_SYNC, 1)
        }
        val id = provider.insert(groupsUri, values)?.let(ContentUris::parseId)
            ?: throw IllegalStateException("Gruppe „$category“ konnte nicht angelegt werden")
        groups[id] = LocalGroup(id, category, dirty = false, deleted = false)
        return id
    }

    private fun deleteUnusedGroups() {
        val used = provider.query(
            dataUri, arrayOf(GroupMembership.GROUP_ROW_ID), "${Data.MIMETYPE} = ?", arrayOf(GroupMembership.CONTENT_ITEM_TYPE), null,
        )?.use { c -> buildSet<Long> { while (c.moveToNext()) add(c.getLong(0)) } } ?: return
        val ops = Batch(provider)
        for (g in groups.values.filter { it.id !in used }) ops += ContentProviderOperation.newDelete(groupUri(g.id)).build()
        ops.flush()
    }

    /** Kontakte ohne Gruppe sollen in der Kontakte-App sichtbar sein. */
    private fun ensureVisible() {
        val uri = ContactsContract.Settings.CONTENT_URI.asSyncAdapter()
        val values = ContentValues().apply {
            put(ContactsContract.Settings.ACCOUNT_NAME, account.name)
            put(ContactsContract.Settings.ACCOUNT_TYPE, account.type)
            put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1)
            put(ContactsContract.Settings.SHOULD_SYNC, 1)
        }
        val where = "${ContactsContract.Settings.ACCOUNT_NAME} = ? AND ${ContactsContract.Settings.ACCOUNT_TYPE} = ?"
        val args = arrayOf(account.name, account.type)
        if (provider.update(uri, values, where, args) == 0) provider.insert(uri, values)
    }

    /** Kontakt mit Foto (die Liste enthält keine Fotos) */
    private suspend fun full(c: Contact): Contact = if (c.hasPhoto) apiCall { api.contact(c.id) } else c

    private fun localContacts(): List<LocalContact> =
        provider.query(
            rawContactsUri,
            arrayOf(
                RawContacts._ID, RawContacts.SOURCE_ID, RawContacts.SYNC1, RawContacts.SYNC2, RawContacts.SYNC3,
                RawContacts.DIRTY, RawContacts.DELETED, RawContacts.SYNC4,
            ),
            "${RawContacts.ACCOUNT_NAME} = ? AND ${RawContacts.ACCOUNT_TYPE} = ?", arrayOf(account.name, account.type), null,
        )?.use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        LocalContact(
                            id = c.getLong(0), serverId = c.getString(1), etag = c.getString(2),
                            addressBookId = c.getString(3), photoMarker = c.getString(4),
                            dirty = c.getInt(5) != 0, deleted = c.getInt(6) != 0,
                            categoriesMapped = c.getString(7) in listOf(CATEGORIES_MAPPED, SYNC_FORMAT),
                            currentFormat = c.getString(7) == SYNC_FORMAT,
                        )
                    )
                }
            }
        } ?: emptyList()

    // ---------- Hochladen ----------

    private suspend fun upload(c: LocalContact, addressBookId: String) {
        if (c.deleted) {
            if (c.serverId != null) {
                try {
                    apiCall { api.deleteContact(c.serverId) }
                } catch (e: ApiException) {
                    if (e.status != 404) throw e
                }
                result.stats.numDeletes++
            }
            provider.delete(rawContactUri(c.id), null, null)
            return
        }
        val input = readLocal(c, addressBookId)
        val saved = if (c.serverId == null) {
            apiCall { api.createContact(input) }.also { result.stats.numInserts++ }
        } else {
            try {
                apiCall { api.updateContact(c.serverId, input) }
            } catch (e: ApiException) {
                // Auf dem Server inzwischen gelöscht: die Änderung vom Gerät nicht verwerfen
                if (e.status != 404) throw e
                apiCall { api.createContact(input.copy(photo = input.photo ?: currentPhoto(c.id))) }
            }.also { result.stats.numUpdates++ }
        }
        // Normalisierten Stand des Servers übernehmen (setzt DIRTY zurück)
        write(c.id, saved)
    }

    private fun readLocal(c: LocalContact, addressBookId: String): ContactInput {
        var name: ContactInput? = null
        var realName: List<String>? = null
        var nickname = ""
        var org = Triple("", "", "")
        val phones = mutableListOf<TypedValue>()
        val emails = mutableListOf<TypedValue>()
        val addresses = mutableListOf<PostalAddress>()
        val urls = mutableListOf<TypedValue>()
        var birthday = ""
        val notes = mutableListOf<String>()
        val categories = mutableListOf<String>()
        var photoMarker = ""

        val columns = arrayOf(
            Data._ID, Data.MIMETYPE, Data.DATA_VERSION,
            Data.DATA1, Data.DATA2, Data.DATA3, Data.DATA4, Data.DATA5, Data.DATA6, Data.DATA7, Data.DATA8, Data.DATA9,
            Data.DATA10,
        )
        provider.query(dataUri, columns, "${Data.RAW_CONTACT_ID} = ?", arrayOf(c.id.toString()), null)?.use { cur ->
            fun s(i: Int) = cur.getString(i)?.trim().orEmpty()
            // DATA1 … DATA10 liegen in den Spalten 3 … 12
            fun d(n: Int) = s(n + 2)
            fun type() = cur.getString(4)?.toIntOrNull() ?: 0
            while (cur.moveToNext()) {
                when (cur.getString(1)) {
                    StructuredName.CONTENT_ITEM_TYPE -> if (name == null) name = emptyInput(addressBookId).copy(
                        fullName = d(1), givenName = d(2), familyName = d(3), prefix = d(4), additionalNames = d(5),
                        suffix = d(6),
                    )
                    REAL_NAME_MIMETYPE -> if (realName == null) realName = (1..6).map { d(it) }
                    Nickname.CONTENT_ITEM_TYPE -> if (nickname.isEmpty()) nickname = d(1)
                    Organization.CONTENT_ITEM_TYPE -> if (org.first.isEmpty() && org.second.isEmpty() && org.third.isEmpty()) {
                        // COMPANY = DATA1, TITLE = DATA4, DEPARTMENT = DATA5
                        org = Triple(d(1), d(5), d(4))
                    }
                    Phone.CONTENT_ITEM_TYPE -> if (d(1).isNotEmpty()) phones += TypedValue(
                        phoneTypeFromAndroid(type(), d(3)) { Phone.getTypeLabel(resources, it, null).toString() }, d(1),
                    )
                    Email.CONTENT_ITEM_TYPE -> if (d(1).isNotEmpty()) emails += TypedValue(
                        emailTypeFromAndroid(type(), d(3)) { Email.getTypeLabel(resources, it, null).toString() }, d(1),
                    )
                    StructuredPostal.CONTENT_ITEM_TYPE -> {
                        // FORMATTED = DATA1, STREET = DATA4, POBOX = DATA5, NEIGHBORHOOD = DATA6, CITY = DATA7,
                        // REGION = DATA8, POSTCODE = DATA9, COUNTRY = DATA10
                        val structured = (4..10).any { d(it).isNotEmpty() }
                        val a = PostalAddress(
                            type = postalTypeFromAndroid(type(), d(3)),
                            street = if (structured) d(4) else d(1), poBox = d(5), extended = d(6), locality = d(7),
                            region = d(8), postalCode = d(9), country = d(10),
                        )
                        if (structured || d(1).isNotEmpty()) addresses += a
                    }
                    Website.CONTENT_ITEM_TYPE -> if (d(1).isNotEmpty()) urls += TypedValue(websiteTypeFromAndroid(type(), d(3)), d(1))
                    Event.CONTENT_ITEM_TYPE -> if (type() == Event.TYPE_BIRTHDAY && birthday.isEmpty()) birthday = normalizeBirthday(d(1))
                    Note.CONTENT_ITEM_TYPE -> if (d(1).isNotEmpty()) notes += cur.getString(3).orEmpty().trimEnd()
                    GroupMembership.CONTENT_ITEM_TYPE -> d(1).toLongOrNull()?.let { groups[it] }?.let { categories += it.title }
                    Photo.CONTENT_ITEM_TYPE -> photoMarker = "${cur.getLong(0)}:${cur.getInt(2)}"
                }
            }
        }
        // Foto nur übertragen, wenn es auf dem Gerät geändert wurde
        val photo = when {
            photoMarker == c.photoMarker.orEmpty() -> null
            photoMarker.isEmpty() -> ""
            else -> currentPhoto(c.id) ?: ""
        }
        // Steht als Name noch der geschriebene Spitzname, gelten die bewahrten Namensteile; wurde der Name auf dem
        // Gerät geändert, die Eingabe von dort.
        val n = name
        val kept = realName
        if (n != null && kept != null && n.fullName == kept[0]) {
            name = n.copy(
                fullName = "", prefix = kept[1], givenName = kept[2], additionalNames = kept[3], familyName = kept[4],
                suffix = kept[5],
            )
        }
        return (name ?: emptyInput(addressBookId)).copy(
            addressBookId = addressBookId,
            nickname = nickname,
            organization = org.first, department = org.second, title = org.third,
            phones = phones, emails = emails, addresses = addresses, urls = urls,
            birthday = birthday,
            note = notes.joinToString("\n"),
            // Vor dieser App-Version geschriebene Kontakte haben noch keine Gruppen: Kategorien des Servers behalten
            categories = if (c.categoriesMapped || categories.isNotEmpty()) cleanCategories(categories) else null,
            photo = photo,
        )
    }

    private fun emptyInput(addressBookId: String) = ContactInput(
        addressBookId = addressBookId, fullName = "", prefix = "", givenName = "", additionalNames = "",
        familyName = "", suffix = "", nickname = "", organization = "", department = "", title = "",
        phones = emptyList(), emails = emptyList(), addresses = emptyList(), urls = emptyList(), birthday = "", note = "",
    )

    /** Foto des Kontakts als data:-URL – das große Anzeigefoto, ersatzweise das Vorschaubild. */
    private fun currentPhoto(rawContactId: Long): String? {
        val displayPhoto = Uri.withAppendedPath(
            ContentUris.withAppendedId(RawContacts.CONTENT_URI, rawContactId), RawContacts.DisplayPhoto.CONTENT_DIRECTORY,
        )
        val bytes = runCatching {
            provider.openAssetFile(displayPhoto, "r")?.use { it.createInputStream().readBytes() }
        }.getOrNull() ?: provider.query(
            dataUri, arrayOf(Photo.PHOTO), "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawContactId.toString(), Photo.CONTENT_ITEM_TYPE), null,
        )?.use { c -> if (c.moveToFirst()) c.getBlob(0) else null }
        if (bytes == null || bytes.isEmpty()) return null
        return "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    // ---------- Schreiben ----------

    /** Legt den Kontakt an ([rawId] = null) oder ersetzt seine Daten durch den Stand des Servers. */
    private fun write(rawId: Long?, c: Contact) {
        val ops = Batch(provider, maxSize = Int.MAX_VALUE)
        val photo = c.photo?.let(::decodePhoto)
        val rawValues = ContentValues().apply {
            put(RawContacts.SOURCE_ID, c.id)
            put(RawContacts.SYNC1, c.etag)
            put(RawContacts.SYNC2, c.addressBookId)
            put(RawContacts.DIRTY, 0)
            put(RawContacts.SYNC4, SYNC_FORMAT)
            // Stand des Fotos: ohne Foto sofort bekannt, sonst nach dem Schreiben (rememberPhoto)
            if (photo == null) put(RawContacts.SYNC3, "")
        }
        val back: Int?
        if (rawId == null) {
            rawValues.put(RawContacts.ACCOUNT_NAME, account.name)
            rawValues.put(RawContacts.ACCOUNT_TYPE, account.type)
            ops.add(ContentProviderOperation.newInsert(rawContactsUri).withValues(rawValues).build())
            back = 0
        } else {
            ops.add(ContentProviderOperation.newUpdate(rawContactUri(rawId)).withValues(rawValues).build())
            ops.add(
                ContentProviderOperation.newDelete(dataUri).withSelection(
                    "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} IN (${MANAGED_MIMETYPES.joinToString { "?" }})",
                    arrayOf(rawId.toString()) + MANAGED_MIMETYPES,
                ).build()
            )
            back = null
        }
        fun row(mimetype: String, values: ContentValues.() -> Unit) {
            val b = ContentProviderOperation.newInsert(dataUri)
            if (back != null) b.withValueBackReference(Data.RAW_CONTACT_ID, back) else b.withValue(Data.RAW_CONTACT_ID, rawId)
            ops.add(b.withValues(ContentValues().apply { put(Data.MIMETYPE, mimetype); values() }).build())
        }

        // Ein Spitzname ist der Anzeigename. Android bildet ihn aber aus Vor- und Nachname, sobald es sie gibt:
        // Deshalb steht dann nur der Spitzname als Name auf dem Gerät, die echten Namensteile in einer eigenen Zeile.
        // Ohne Namensteile leitet der Server den Anzeigenamen aus Firma, E-Mail oder Telefon ab – genauso wie
        // Android. Den abgeleiteten Namen nicht als Namen speichern, sonst zerlegt Android ihn in Vor-/Nachname.
        val nickname = c.nickname.trim()
        val nameParts = listOf(c.prefix, c.givenName, c.additionalNames, c.familyName, c.suffix)
        val derived = c.fullName.isBlank() || c.fullName == c.organization ||
            c.emails.any { it.value == c.fullName } || c.phones.any { it.value == c.fullName }
        if (nickname.isNotEmpty()) {
            row(StructuredName.CONTENT_ITEM_TYPE) { put(StructuredName.DISPLAY_NAME, nickname) }
            row(REAL_NAME_MIMETYPE) {
                put(Data.DATA1, nickname)
                nameParts.forEachIndexed { i, part -> put("data${i + 2}", part) }
            }
        } else if (nameParts.any { it.isNotBlank() } || !derived) row(StructuredName.CONTENT_ITEM_TYPE) {
            put(StructuredName.DISPLAY_NAME, c.fullName)
            put(StructuredName.PREFIX, c.prefix)
            put(StructuredName.GIVEN_NAME, c.givenName)
            put(StructuredName.MIDDLE_NAME, c.additionalNames)
            put(StructuredName.FAMILY_NAME, c.familyName)
            put(StructuredName.SUFFIX, c.suffix)
        }
        if (c.nickname.isNotBlank()) row(Nickname.CONTENT_ITEM_TYPE) { put(Nickname.NAME, c.nickname) }
        if (listOf(c.organization, c.department, c.title).any { it.isNotBlank() }) {
            row(Organization.CONTENT_ITEM_TYPE) {
                put(Organization.COMPANY, c.organization)
                put(Organization.DEPARTMENT, c.department)
                put(Organization.TITLE, c.title)
                put(Organization.TYPE, Organization.TYPE_WORK)
            }
        }
        for (p in c.phones) row(Phone.CONTENT_ITEM_TYPE) {
            val t = phoneTypeToAndroid(p.type)
            put(Phone.NUMBER, p.value); put(Phone.TYPE, t.type); put(Phone.LABEL, t.label)
        }
        for (e in c.emails) row(Email.CONTENT_ITEM_TYPE) {
            val t = emailTypeToAndroid(e.type)
            put(Email.ADDRESS, e.value); put(Email.TYPE, t.type); put(Email.LABEL, t.label)
        }
        for (a in c.addresses) row(StructuredPostal.CONTENT_ITEM_TYPE) {
            val t = postalTypeToAndroid(a.type)
            put(StructuredPostal.TYPE, t.type); put(StructuredPostal.LABEL, t.label)
            put(StructuredPostal.STREET, a.street)
            put(StructuredPostal.POBOX, a.poBox)
            put(StructuredPostal.NEIGHBORHOOD, a.extended)
            put(StructuredPostal.CITY, a.locality)
            put(StructuredPostal.REGION, a.region)
            put(StructuredPostal.POSTCODE, a.postalCode)
            put(StructuredPostal.COUNTRY, a.country)
            put(
                StructuredPostal.FORMATTED_ADDRESS,
                formatAddress(a.street, a.extended, a.poBox, a.postalCode, a.locality, a.region, a.country),
            )
        }
        for (u in c.urls) row(Website.CONTENT_ITEM_TYPE) {
            val t = websiteTypeToAndroid(u.type)
            put(Website.URL, u.value); put(Website.TYPE, t.type); put(Website.LABEL, t.label)
        }
        if (c.birthday.isNotBlank()) row(Event.CONTENT_ITEM_TYPE) {
            put(Event.TYPE, Event.TYPE_BIRTHDAY)
            put(Event.START_DATE, c.birthday)
        }
        if (c.note.isNotBlank()) row(Note.CONTENT_ITEM_TYPE) { put(Note.NOTE, c.note) }
        for (cat in c.categories) {
            val group = groupFor(cat)
            row(GroupMembership.CONTENT_ITEM_TYPE) { put(GroupMembership.GROUP_ROW_ID, group) }
        }
        if (photo != null) row(Photo.CONTENT_ITEM_TYPE) { put(Photo.PHOTO, photo) }

        val results = ops.flush()
        if (photo == null) return
        val id = rawId ?: results.firstOrNull()?.uri?.let(ContentUris::parseId) ?: return
        rememberPhoto(id)
    }

    /** Merkt sich den Stand des Fotos, wie ihn der Provider gespeichert hat, um spätere Änderungen zu erkennen. */
    private fun rememberPhoto(rawId: Long) {
        val marker = provider.query(
            dataUri, arrayOf(Data._ID, Data.DATA_VERSION), "${Data.RAW_CONTACT_ID} = ? AND ${Data.MIMETYPE} = ?",
            arrayOf(rawId.toString(), Photo.CONTENT_ITEM_TYPE), null,
        )?.use { c -> if (c.moveToFirst()) "${c.getLong(0)}:${c.getInt(1)}" else "" } ?: ""
        provider.update(rawContactUri(rawId), ContentValues().apply { put(RawContacts.SYNC3, marker) }, null, null)
    }

    /** data:-URL → JPEG/PNG-Bytes, große Fotos verkleinert; `null` bei http(s)-Links oder ungültigen Daten. */
    private fun decodePhoto(dataUrl: String): ByteArray? {
        if (!dataUrl.startsWith("data:")) return null
        val base64 = dataUrl.substringAfter(',', "").takeIf { it.isNotEmpty() } ?: return null
        val bytes = runCatching { Base64.decode(base64, Base64.DEFAULT) }.getOrNull() ?: return null
        if (bytes.size <= MAX_PHOTO_BYTES) return bytes
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val scale = MAX_PHOTO_SIZE.toFloat() / maxOf(bitmap.width, bitmap.height)
        val scaled = if (scale < 1f) {
            bitmap.scale((bitmap.width * scale).toInt(), (bitmap.height * scale).toInt())
        } else bitmap
        return ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out)
            out.toByteArray()
        }
    }

    private suspend fun itemSafe(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: ApiException) {
            if (e.status == 401 || e.status >= 500) throw e
            Log.w(TAG, "Kontakt übersprungen: ${e.message}")
            result.stats.numSkippedEntries++
            if (firstItemError == null) firstItemError = e
        }
    }
}
