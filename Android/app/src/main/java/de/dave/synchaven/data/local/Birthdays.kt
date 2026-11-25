package de.dave.synchaven.data.local

import de.dave.synchaven.R
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.util.displayName
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Birthday calendar in local mode – like `backend/src/birthdays.rs`: for each contact with a birthday there is
 * a yearly, all-day, read-only event in the birthday calendar. [OfflineData.occurrences] adds the age
 * to the title from the contact's birth year.
 */
object Birthdays {
    const val KIND = "birthdays"
    private const val COLOR = "#D81B60"
    /** Year for birthdays without a year (a leap year, so Feb 29 is valid too) */
    private const val NO_YEAR = 2000

    /** Birthday from `YYYY-MM-DD` or `--MM-DD`: date of the first event and birth year, if known */
    fun parse(birthday: String): Pair<LocalDate, Int?>? {
        val b = birthday.trim()
        if (b.startsWith("--")) {
            return runCatching { LocalDate.parse("$NO_YEAR-${b.removePrefix("--")}") }.getOrNull()?.let { it to null }
        }
        if (!Regex("""\d{4}-\d{2}-\d{2}""").matches(b)) return null
        return runCatching { LocalDate.parse(b) }.getOrNull()?.let { it to it.year }
    }

    /** Yearly recurrence; people born on Feb 29 celebrate on Feb 28 in non-leap years. */
    fun rruleFor(date: LocalDate) =
        if (date.monthValue == 2 && date.dayOfMonth == 29) "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1" else "FREQ=YEARLY"

    /**
     * Syncs the birthday events with the contacts: create, update, remove. The calendar is created
     * with the first birthday and stays afterwards (it can be hidden but not deleted). Name and descriptions come
     * from [texts], like the server's in the same language; an existing calendar keeps its name.
     */
    fun sync(data: OfflineData, newId: () -> String, now: String, texts: LocalTexts): OfflineData {
        val wanted = data.contacts.orEmpty().mapNotNull { c -> parse(c.birthday)?.let { c to it } }
        var calendars = data.calendars.orEmpty()
        var calendar = calendars.firstOrNull { it.kind == KIND }
        if (calendar == null) {
            if (wanted.isEmpty()) return data
            calendar = Calendar(
                id = newId(), name = texts.get(R.string.api_birthdays_calendar_name), color = COLOR,
                description = texts.get(R.string.api_birthdays_calendar_description), kind = KIND, createdAt = now, updatedAt = now,
            )
            calendars = calendars + calendar
        }
        val dateFormat = DateTimeFormatter.ofPattern(
            texts.get(R.string.api_birthdays_date_pattern), Locale.forLanguageTag(texts.get(R.string.api_language)),
        )
        val existing = data.events.orEmpty().filter { it.contactId != null }.associateBy { it.contactId }
        val birthdays = wanted.map { (c, b) ->
            val (date, year) = b
            val start = date.atStartOfDay(ZoneOffset.UTC).toInstant()
            CalEvent(
                id = existing[c.id]?.id ?: newId(),
                calendarId = calendar.id,
                uid = "birthday-${c.id}@synchaven",
                title = "🎂 ${c.displayName()}",
                description = if (year != null) texts.get(R.string.api_birthdays_born_on, date.format(dateFormat)) else "",
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
