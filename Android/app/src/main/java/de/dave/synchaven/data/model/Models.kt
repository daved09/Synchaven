package de.dave.synchaven.data.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import de.dave.synchaven.util.ReminderFieldSerializer
import de.dave.synchaven.util.Reminders

// Data models of the REST API (see docs/API.md). Times stay strings in RFC 3339 format;
// the UI layer converts them to java.time (util/Dates.kt).

@Serializable
data class ServerInfo(
    val name: String = "",
    val version: String = "",
    @SerialName("api_version") val apiVersion: Int = 1,
    @SerialName("allow_registration") val allowRegistration: Boolean = false,
    /** No account exists yet: the administrator is created in the web interface with the setup token */
    @SerialName("setup_required") val setupRequired: Boolean = false,
)

@Serializable
data class User(
    val id: String,
    val email: String,
    val name: String = "",
    /** The calendar's default view: `month`, `week`, `day` or `list` */
    @SerialName("default_view") val defaultView: String = "month",
    @SerialName("created_at") val createdAt: String = "",
    /** Sign-in needs a code of an authenticator app */
    @SerialName("totp_enabled") val totpEnabled: Boolean = false,
)

/**
 * Response of `PUT /auth/me`: the account and – after a password change, which ends all other sessions – the new
 * token for this one (`null` otherwise).
 */
@Serializable(with = UpdateMeResponseSerializer::class)
data class UpdateMeResponse(val user: User, val token: String? = null, val expiresAt: String = "")

@Serializable
internal class SessionPart(val token: String? = null, @SerialName("expires_at") val expiresAt: String = "")

/** The server sends the account's fields and the token in one object */
object UpdateMeResponseSerializer : KSerializer<UpdateMeResponse> {
    override val descriptor: SerialDescriptor = User.serializer().descriptor

    override fun deserialize(decoder: Decoder): UpdateMeResponse {
        val json = decoder as JsonDecoder
        val element = json.decodeJsonElement()
        val session = json.json.decodeFromJsonElement(SessionPart.serializer(), element)
        return UpdateMeResponse(json.json.decodeFromJsonElement(User.serializer(), element), session.token, session.expiresAt)
    }

    override fun serialize(encoder: Encoder, value: UpdateMeResponse) = User.serializer().serialize(encoder, value.user)
}

@Serializable
data class AuthResponse(
    val token: String,
    @SerialName("expires_at") val expiresAt: String = "",
    val user: User,
)

@Serializable
data class LoginRequest(
    val email: String,
    val password: String,
    /** Code of the authenticator app or a recovery code, for accounts with two-factor authentication */
    val code: String? = null,
)

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

// ---------- Calendars ----------

@Serializable
data class Calendar(
    val id: String,
    val name: String,
    val color: String = "#6750A4",
    val description: String = "",
    val visible: Boolean = true,
    /** `default` or `birthdays` (generated automatically from the contacts) */
    val kind: String = "default",
    /** Default reminders (minutes before the start) for timed events */
    @SerialName("default_reminders") val defaultReminders: List<Int> = Reminders.DEFAULT_TIMED,
    /** Default reminders for all-day events (negative = time on the day itself) */
    @SerialName("default_all_day_reminders") val defaultAllDayReminders: List<Int> = Reminders.DEFAULT_ALL_DAY,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    /** Without default reminders – the server then leaves them unchanged */
    fun toInput() = CalendarInput(name, color, description, visible)

    fun defaultReminders(allDay: Boolean) = if (allDay) defaultAllDayReminders else defaultReminders

    /** Birthday calendar: cannot be deleted, events read-only */
    val isBirthdays get() = kind == "birthdays"
}

@Serializable
data class CalendarInput(
    val name: String,
    val color: String,
    val description: String = "",
    val visible: Boolean = true,
    /** `null`: the existing default reminders are kept */
    @SerialName("default_reminders") val defaultReminders: List<Int>? = null,
    @SerialName("default_all_day_reminders") val defaultAllDayReminders: List<Int>? = null,
)

@Serializable
data class ImportResult(val imported: Int = 0, val skipped: List<String> = emptyList())

/** Event, or for series the series itself */
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
    /** Reminders in minutes before the start; `null` = calendar default */
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
     * Reminders, see [remindersField]: `null` (field missing) leaves them unchanged, `JsonNull` sets the
     * calendar default, a list sets own reminders.
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @Serializable(with = ReminderFieldSerializer::class)
    val reminders: JsonElement? = null,
)

/** Single occurrence of an event (series are expanded by the server) */
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
    /** Contact whose birthday the event was generated from */
    @SerialName("contact_id") val contactId: String? = null,
    /** Automatically generated events (birthdays) cannot be edited */
    @SerialName("read_only") val readOnly: Boolean = false,
    /** The event's reminders; `null` = calendar default */
    val reminders: List<Int>? = null,
)

@Serializable
data class ExdateRequest(@SerialName("occurrence_start") val occurrenceStart: String)

// ---------- Contacts ----------

@Serializable
data class AddressBook(
    val id: String,
    val name: String,
    val description: String = "",
    @SerialName("contact_count") val contactCount: Int = 0,
)

@Serializable
data class AddressBookInput(val name: String, val description: String = "")

/** Phone number, email address or website with a type (`mobile`, `home`, `work`, … or a custom label) */
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
    /** Changes with every change of the contact (also of the photo) – for syncing */
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
    /** `YYYY-MM-DD` or `--MM-DD` (without year) */
    val birthday: String = "",
    val note: String = "",
    /** Categories (vCard `CATEGORIES`) */
    val categories: List<String> = emptyList(),
    @SerialName("has_photo") val hasPhoto: Boolean = false,
    /** only for single queries: data: URL */
    val photo: String? = null,
) {
    fun toInput() = ContactInput(
        addressBookId = addressBookId, fullName = fullName, prefix = prefix, givenName = givenName,
        additionalNames = additionalNames, familyName = familyName, suffix = suffix, nickname = nickname,
        organization = organization, department = department, title = title, phones = phones,
        emails = emails, addresses = addresses, urls = urls, birthday = birthday, note = note, categories = categories,
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
    /** `null` leaves the categories unchanged */
    val categories: List<String>? = null,
    /** New photo as a data: URL, `""` removes it, `null` leaves it unchanged */
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

// ---------- Tasks ----------

@Serializable
data class TaskList(
    val id: String,
    val name: String,
    val color: String = "#1A73E8",
    /** Show this list's tasks in the calendar */
    val visible: Boolean = true,
    /** Number of open tasks */
    @SerialName("open_count") val openCount: Int = 0,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
) {
    fun toInput() = TaskListInput(name, color, visible)
}

/** `color` and `visible` are optional; when editing, missing values are kept. */
@Serializable
data class TaskListInput(
    val name: String,
    val color: String? = null,
    val visible: Boolean? = null,
)

/**
 * Task. [due] follows the events' time model: all-day as midnight UTC of the day, otherwise a point in time in
 * UTC; `null` = without date (then [allDay] is always `true`).
 */
@Serializable
data class Task(
    val id: String,
    @SerialName("list_id") val listId: String,
    val title: String,
    val notes: String = "",
    val due: String? = null,
    @SerialName("all_day") val allDay: Boolean = true,
    val completed: Boolean = false,
    @SerialName("completed_at") val completedAt: String? = null,
    @SerialName("created_at") val createdAt: String = "",
    @SerialName("updated_at") val updatedAt: String = "",
    /** Parent task if this is a subtask */
    @SerialName("parent_id") val parentId: String? = null,
) {
    /** Input for saving with unchanged status (`completed` missing) */
    fun toInput() = TaskInput(listId, title, notes, due, allDay, parentId = parentId)
}

@Serializable
data class TaskInput(
    @SerialName("list_id") val listId: String,
    val title: String,
    val notes: String = "",
    val due: String? = null,
    @SerialName("all_day") val allDay: Boolean = true,
    /** `null` leaves the status unchanged (new tasks are open) */
    val completed: Boolean? = null,
    /**
     * Parent task in the same list. `null` is not sent and leaves the assignment unchanged (new
     * tasks: top-level task); a subtask in another list becomes a top-level task.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    @SerialName("parent_id") val parentId: String? = null,
)

@Serializable
data class CompletedRequest(val completed: Boolean)

@Serializable
data class ClearCompletedResult(val deleted: Int = 0)
