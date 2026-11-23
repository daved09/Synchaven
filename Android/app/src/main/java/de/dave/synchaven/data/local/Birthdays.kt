package de.dave.synchaven.data.local

import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.util.displayName
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Geburtstagskalender im lokalen Modus – wie `backend/src/birthdays.rs`: Für jeden Kontakt mit Geburtstag gibt es
 * einen jährlichen, ganztägigen, schreibgeschützten Termin im Kalender „Geburtstage“. Das Alter im Titel ergänzt
 * [OfflineData.occurrences] aus dem Geburtsjahr des Kontakts.
 */
object Birthdays {
    const val KIND = "birthdays"
    private const val NAME = "Geburtstage"
    private const val COLOR = "#D81B60"
    private const val DESCRIPTION = "Wird automatisch aus den Kontakten erstellt"
    /** Jahr für Geburtstage ohne Jahresangabe (Schaltjahr, damit auch der 29.02. gültig ist) */
    private const val NO_YEAR = 2000
    private val GERMAN_DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy")

    /** Geburtstag aus `YYYY-MM-DD` oder `--MM-DD`: Datum des ersten Termins und Geburtsjahr, falls bekannt */
    fun parse(birthday: String): Pair<LocalDate, Int?>? {
        val b = birthday.trim()
        if (b.startsWith("--")) {
            return runCatching { LocalDate.parse("$NO_YEAR-${b.removePrefix("--")}") }.getOrNull()?.let { it to null }
        }
        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(b)) return null
        return runCatching { LocalDate.parse(b) }.getOrNull()?.let { it to it.year }
    }

    /** Jährliche Wiederholung; am 29.02. Geborene feiern in Nicht-Schaltjahren am 28.02. */
    fun rruleFor(date: LocalDate) =
        if (date.monthValue == 2 && date.dayOfMonth == 29) "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1" else "FREQ=YEARLY"

    /**
     * Gleicht die Geburtstagstermine mit den Kontakten ab: anlegen, aktualisieren, entfernen. Der Kalender entsteht
     * beim ersten Geburtstag und bleibt danach bestehen (er kann ausgeblendet, aber nicht gelöscht werden).
     */
    fun sync(data: OfflineData, newId: () -> String, now: String): OfflineData {
        val wanted = data.contacts.orEmpty().mapNotNull { c -> parse(c.birthday)?.let { c to it } }
        var calendars = data.calendars.orEmpty()
        var calendar = calendars.firstOrNull { it.kind == KIND }
        if (calendar == null) {
            if (wanted.isEmpty()) return data
            calendar = Calendar(
                id = newId(), name = NAME, color = COLOR, description = DESCRIPTION, kind = KIND, createdAt = now, updatedAt = now,
            )
            calendars = calendars + calendar
        }
        val existing = data.events.orEmpty().filter { it.contactId != null }.associateBy { it.contactId }
        val birthdays = wanted.map { (c, b) ->
            val (date, year) = b
            val start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
            CalEvent(
                id = existing[c.id]?.id ?: newId(),
                calendarId = calendar.id,
                uid = "birthday-${c.id}@synchaven",
                title = "🎂 ${c.displayName()}",
                description = if (year != null) "Geboren am ${date.format(GERMAN_DATE)}" else "",
                start = start.toString(),
                end = start.plusSeconds(86_400).toString(),
                allDay = true,
                timezone = "UTC",
                rrule = rruleFor(date),
                contactId = c.id,
                readOnly = true,
            )
        }
        return data.copy(calendars = calendars, events = data.events.orEmpty().filter { it.contactId == null } + birthdays)
    }
}
