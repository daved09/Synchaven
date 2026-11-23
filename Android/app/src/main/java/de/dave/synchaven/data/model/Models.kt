package de.dave.synchaven.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import de.dave.synchaven.util.ReminderFieldSerializer
import de.dave.synchaven.util.Reminders

// Datenmodelle der REST API (siehe docs/API.md). Zeitangaben bleiben Strings im RFC-3339-Format;
// die Umrechnung in java.time übernimmt die UI-Schicht (util/Dates.kt).

@Serializable
data class ServerInfo(
    val name: String = "",
    val version: String = "",
    @SerialName("api_version") val apiVersion: Int = 1,
    @SerialName("allow_registration") val allowRegistration: Boolean = false,
)

@Serializable
data class User(
    val id: String,
    val email: String,
    val name: String = "",
    /** Standardansicht des Kalenders: `month`, `week`, `day` oder `list` */
    @SerialName("default_view") val defaultView: String = "month",
    @SerialName("created_at") val createdAt: String = "",
)

@Serializable
data class AuthResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String = "",
    val user: User,
)

@Serializable
data class LoginRequest(val email: String, val password: String)

@Serializable
data class RegisterRequest(val email: String, val password: String, val name: String)

@Serializable
data class UpdateMeRequest(
    val name: String? = null,
    val email: String? = null,
    @SerialName("current_password") val currentPassword: String? = null,
    @SerialName("new_password") val newPassword: String? = null,
    @SerialName("default_view") val defaultView: String? = null,
)

@Serializable
data class ApiErrorBody(val error: String = "", val message: String = "")

// ---------- Kalender ----------

@Serializable
data class Calendar(
    val id: String,
    val name: String,
    val color: String = "#6750A4",
    val description: String = "",
    val visible: Boolean = true,
    /** `default` oder `birthdays` (automatisch aus den Kontakten erzeugt) */
    val kind: String = "default",
    /** Standard-Erinnerungen (Minuten vor Beginn) für Termine mit Uhrzeit */
    @SerialName("default_reminders") val defaultReminders: List<Int> = Reminders.DEFAULT_TIMED,
    /** Standard-Erinnerungen für ganztägige Termine (negativ = Uhrzeit am Tag selbst) */
    @SerialName("default_all_day_reminders") val defaultAllDayReminders: List<Int> = Reminders.DEFAULT_ALL_DAY,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    /** Ohne Standard-Erinnerungen – der Server lässt sie dann unverändert */
    fun toInput() = CalendarInput(name, color, description, visible)

    fun defaultReminders(allDay: Boolean) = if (allDay) defaultAllDayReminders else defaultReminders

    /** Geburtstagskalender: nicht löschbar, Termine schreibgeschützt */
    val isBirthdays get() = kind == "birthdays"
}

@Serializable
data class CalendarInput(
    val name: String,
    val color: String,
    val description: String = "",
    val visible: Boolean = true,
    /** `null`: bisherige Standard-Erinnerungen bleiben erhalten */
    @SerialName("default_reminders") val defaultReminders: List<Int>? = null,
    @SerialName("default_all_day_reminders") val defaultAllDayReminders: List<Int>? = null,
)

@Serializable
data class ImportResult(val imported: Int = 0, val skipped: List<String> = emptyList())

/** Termin bzw. bei Serien die Serie selbst */
@Serializable
data class CalEvent(
    val id: String,
    @SerialName("calendar_id") val calendarId: String,
    val uid: String = "",
    val title: String,
    val description: String = "",
    val location: String = "",
    val start: String,
    val end: String,
    @SerialName("all_day") val allDay: Boolean = false,
    val timezone: String = "UTC",
    val rrule: String? = null,
    val exdates: List<String> = emptyList(),
    /** Erinnerungen in Minuten vor Beginn; `null` = Standard des Kalenders */
    val reminders: List<Int>? = null,
    @SerialName("contact_id") val contactId: String? = null,
    @SerialName("read_only") val readOnly: Boolean = false,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EventInput(
    @SerialName("calendar_id") val calendarId: String,
    val title: String,
    val description: String,
    val location: String,
    val start: String,
    val end: String,
    @SerialName("all_day") val allDay: Boolean,
    val timezone: String,
    val rrule: String?,
    val exdates: List<String> = emptyList(),
    /**
     * Erinnerungen, siehe [remindersField]: `null` (Feld fehlt) lässt sie unverändert, `JsonNull` setzt den
     * Kalender-Standard, eine Liste eigene Erinnerungen.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = ReminderFieldSerializer::class)
    val reminders: JsonElement? = null,
)

/** Einzelnes Vorkommen eines Termins (Serien werden vom Server expandiert) */
@Serializable
data class Occurrence(
    val id: String,
    @SerialName("event_id") val eventId: String,
    @SerialName("calendar_id") val calendarId: String,
    val title: String,
    val description: String = "",
    val location: String = "",
    val start: String,
    val end: String,
    @SerialName("all_day") val allDay: Boolean = false,
    val timezone: String = "UTC",
    val recurring: Boolean = false,
    val rrule: String? = null,
    /** Kontakt, aus dessen Geburtstag der Termin erzeugt wurde */
    @SerialName("contact_id") val contactId: String? = null,
    /** Automatisch erzeugte Termine (Geburtstage) können nicht bearbeitet werden */
    @SerialName("read_only") val readOnly: Boolean = false,
)

@Serializable
data class ExdateRequest(@SerialName("occurrence_start") val occurrenceStart: String)

// ---------- Kontakte ----------

@Serializable
data class AddressBook(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("contact_count") val contactCount: Int = 0,
)

@Serializable
data class AddressBookInput(val name: String, val description: String = "")

/** Telefonnummer, E-Mail-Adresse oder Website mit Typ (`mobile`, `home`, `work`, … oder eigene Bezeichnung) */
@Serializable
data class TypedValue(val type: String = "", val value: String = "")

@Serializable
data class PostalAddress(
    val type: String = "home",
    @SerialName("po_box") val poBox: String = "",
    val extended: String = "",
    val street: String = "",
    val locality: String = "",
    val region: String = "",
    @SerialName("postal_code") val postalCode: String = "",
    val country: String = "",
)

@Serializable
data class Contact(
    val id: String = "",
    @SerialName("addressbook_id") val addressBookId: String = "",
    val uid: String = "",
    /** Ändert sich mit jeder Änderung des Kontakts (auch des Fotos) – für die Synchronisierung */
    val etag: String = "",
    @SerialName("full_name") val fullName: String = "",
    val prefix: String = "",
    @SerialName("given_name") val givenName: String = "",
    @SerialName("additional_names") val additionalNames: String = "",
    @SerialName("family_name") val familyName: String = "",
    val suffix: String = "",
    val nickname: String = "",
    val organization: String = "",
    val department: String = "",
    val title: String = "",
    val phones: List<TypedValue> = emptyList(),
    val emails: List<TypedValue> = emptyList(),
    val addresses: List<PostalAddress> = emptyList(),
    val urls: List<TypedValue> = emptyList(),
    /** `YYYY-MM-DD` oder `--MM-DD` (ohne Jahr) */
    val birthday: String = "",
    val note: String = "",
    @SerialName("has_photo") val hasPhoto: Boolean = false,
    /** nur bei Einzelabfrage: data:-URL */
    val photo: String? = null,
) {
    fun toInput() = ContactInput(
        addressBookId = addressBookId, fullName = fullName, prefix = prefix, givenName = givenName,
        additionalNames = additionalNames, familyName = familyName, suffix = suffix, nickname = nickname,
        organization = organization, department = department, title = title, phones = phones,
        emails = emails, addresses = addresses, urls = urls, birthday = birthday, note = note,
    )
}

@Serializable
data class ContactInput(
    @SerialName("addressbook_id") val addressBookId: String,
    @SerialName("full_name") val fullName: String,
    val prefix: String,
    @SerialName("given_name") val givenName: String,
    @SerialName("additional_names") val additionalNames: String,
    @SerialName("family_name") val familyName: String,
    val suffix: String,
    val nickname: String,
    val organization: String,
    val department: String,
    val title: String,
    val phones: List<TypedValue>,
    val emails: List<TypedValue>,
    val addresses: List<PostalAddress>,
    val urls: List<TypedValue>,
    val birthday: String,
    val note: String,
    /** Neues Foto als data:-URL, `""` entfernt es, `null` lässt es unverändert */
    val photo: String? = null,
)

@Serializable
data class ContactImportResult(
    val imported: Int = 0,
    val created: Int = 0,
    val updated: Int = 0,
    val unchanged: Int = 0,
    val skipped: List<String> = emptyList(),
)
