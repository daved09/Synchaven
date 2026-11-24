package de.dave.synchaven.data

import de.dave.synchaven.data.local.Birthdays
import de.dave.synchaven.data.local.ICal
import de.dave.synchaven.data.local.LocalApi.Companion.normalizedInput
import de.dave.synchaven.data.local.VCard
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.PostalAddress
import de.dave.synchaven.data.model.TypedValue
import de.dave.synchaven.data.offline.OfflineData
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** iCalendar, vCard and birthdays of the local mode – the same cases as the tests in the backend. */
class LocalFormatsTest {

    // ---------- iCalendar ----------

    @Test
    fun parsesTypicalIcsFile() {
        val ics = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VEVENT\r\nUID:abc\r\nDTSTART;TZID=Europe/Berlin:20261122T100000\r\n" +
            "DTEND;TZID=Europe/Berlin:20261122T113000\r\nSUMMARY:Team\\, Meeting\r\nDESCRIPTION:Zeile 1\\nZeile\r\n  2\r\n" +
            "RRULE:FREQ=WEEKLY;COUNT=3\r\nEXDATE;TZID=Europe/Berlin:20261129T100000\r\nBEGIN:VALARM\r\nACTION:DISPLAY\r\n" +
            "DESCRIPTION:Alarm\r\nEND:VALARM\r\nEND:VEVENT\r\nBEGIN:VEVENT\r\nDTSTART;VALUE=DATE:20261224\r\n" +
            "SUMMARY:Heiligabend\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val evs = ICal.parse(ics, ZoneOffset.UTC)
        assertEquals(2, evs.size)
        val e = evs[0]
        assertEquals("Team, Meeting", e.title)
        assertEquals("Zeile 1\nZeile 2", e.description)
        assertEquals(Instant.parse("2026-11-22T09:00:00Z"), e.start)
        assertEquals(Duration.ofMinutes(90), Duration.between(e.start, e.end))
        assertEquals("Europe/Berlin", e.timezone)
        assertEquals(listOf(Instant.parse("2026-11-29T09:00:00Z")), e.exdates)
        assertEquals("FREQ=WEEKLY;COUNT=3", e.rrule)
        val h = evs[1]
        assertTrue(h.allDay)
        assertEquals(Duration.ofDays(1), Duration.between(h.start, h.end))
        assertEquals("(Ohne Titel)", ICal.parse("BEGIN:VCALENDAR\nBEGIN:VEVENT\nDTSTART:20260101T100000Z\nEND:VEVENT\n", ZoneOffset.UTC)[0].title)
    }

    @Test
    fun icsExportRoundtrip() {
        val ev = CalEvent(
            id = "1", calendarId = "c", uid = "uid-1", title = "Lange; Beschreibung, mit Sonderzeichen äöü 🎂 ".repeat(3),
            description = "a\nb", start = "2026-11-22T09:00:00Z", end = "2026-11-22T10:00:00Z",
            timezone = "Europe/Berlin", rrule = "FREQ=DAILY;COUNT=2", exdates = listOf("2026-11-23T09:00:00Z"),
        )
        val ics = ICal.export("Test", listOf(ev))
        assertTrue(ics.split("\r\n").all { it.toByteArray().size <= 76 })
        assertTrue(ics.contains("DTSTART;TZID=Europe/Berlin:20261122T100000"))
        val back = ICal.parse(ics, ZoneOffset.UTC).single()
        assertEquals(ev.title, back.title)
        assertEquals(Instant.parse(ev.start), back.start)
        assertEquals("a\nb", back.description)
        assertEquals("FREQ=DAILY;COUNT=2", back.rrule)
        assertEquals(listOf(Instant.parse("2026-11-23T09:00:00Z")), back.exdates)
        assertEquals("uid-1", back.uid)
    }

    @Test
    fun icsAllDayUntilIsDate() {
        val ev = CalEvent(
            id = "1", calendarId = "c", uid = "u", title = "T", start = "2026-01-01T00:00:00Z", end = "2026-01-02T00:00:00Z",
            allDay = true, rrule = "FREQ=DAILY;UNTIL=20260105T235959Z",
        )
        val ics = ICal.export("T", listOf(ev))
        assertTrue(ics.contains("DTSTART;VALUE=DATE:20260101"))
        assertTrue(ics.contains("RRULE:FREQ=DAILY;UNTIL=20260105\r\n"))
    }

    @Test
    fun icsRejectsOtherFiles() {
        val e = runCatching { ICal.parse("BEGIN:VCARD\nEND:VCARD", ZoneOffset.UTC) }.exceptionOrNull()
        assertTrue(e is IllegalArgumentException)
    }

    @Test
    fun parsesDurations() {
        assertEquals(Duration.ofMinutes(90), ICal.parseDuration("PT1H30M"))
        assertEquals(Duration.ofDays(14), ICal.parseDuration("P2W"))
        assertNull(ICal.parseDuration("1H"))
    }

    // ---------- vCard ----------

    private val v3 = "BEGIN:VCARD\r\nVERSION:3.0\r\nPRODID:-//DAVx5\r\nUID:abc-1\r\nFN:Erika Mustermann\r\n" +
        "N:Mustermann;Erika;;Dr.;\r\nORG:Firma\\, GmbH;Vertrieb\r\nitem1.TEL:+49 30 1234\r\nitem1.X-ABLabel:Zentrale\r\n" +
        "TEL;TYPE=CELL:0171 555\r\nEMAIL;TYPE=WORK:erika@example.org\r\nADR;TYPE=HOME:;;Heidestr. 17;Köln;;51147;Deutschland\r\n" +
        "BDAY:1964-08-12\r\nNOTE:Zeile 1\\nZeile 2\r\nPHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQ\r\n SkZJRg==\r\nX-CUSTOM:bleibt\r\nEND:VCARD\r\n"

    @Test
    fun readsVCard3() {
        val cards = VCard.parseCards(v3)
        assertEquals(1, cards.size)
        val d = VCard.extract(VCard.normalizeImport(cards[0]))
        assertEquals("abc-1", d.uid)
        assertEquals("Erika Mustermann", d.fullName)
        assertEquals("Mustermann", d.familyName)
        assertEquals("Dr.", d.prefix)
        assertEquals("Firma, GmbH", d.organization)
        assertEquals("Vertrieb", d.department)
        assertEquals(TypedValue("Zentrale", "+49 30 1234"), d.phones[0])
        assertEquals("mobile", d.phones[1].type)
        assertEquals("work", d.emails[0].type)
        assertEquals("Köln", d.addresses[0].locality)
        assertEquals("1964-08-12", d.birthday)
        assertEquals("Zeile 1\nZeile 2", d.note)
        assertEquals("data:image/jpeg;base64,/9j/4AAQSkZJRg==", d.photo)
    }

    @Test
    fun importsVCard21() {
        val v21 = "BEGIN:VCARD\r\nVERSION:2.1\r\nN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:M=C3=BCller;J=C3=BCrgen;;;\r\n" +
            "FN;CHARSET=UTF-8;ENCODING=QUOTED-PRINTABLE:J=C3=BCrgen M=C3=BCller, Sen.\r\nTEL;CELL;PREF:+491701234\r\n" +
            "TEL;HOME:030 12\r\nNOTE;ENCODING=QUOTED-PRINTABLE:Sehr lange Notiz, die umbro=\r\nchen wird=0D=0Aneue Zeile\r\nEND:VCARD\r\n"
        val d = VCard.extract(VCard.normalizeImport(VCard.parseCards(v21).single()))
        assertEquals("Müller", d.familyName)
        assertEquals("Jürgen Müller, Sen.", d.fullName)
        assertEquals(TypedValue("mobile", "+491701234"), d.phones[0])
        assertEquals("home", d.phones[1].type)
        assertEquals("Sehr lange Notiz, die umbrochen wird\nneue Zeile", d.note)
    }

    @Test
    fun vCardCreateRoundtrip() {
        val c = Contact(
            uid = "u1", givenName = "Max", familyName = "Mustermann",
            emails = listOf(TypedValue("home", "max@example.org")),
            phones = listOf(TypedValue("Ferienhaus", "040 1"), TypedValue("mobile", "0171")),
            birthday = "--03-04", note = "Notiz; mit Semikolon",
            addresses = listOf(PostalAddress(type = "work", street = "Weg 1")),
        ).toInput().normalizedInput()
        assertEquals("Max Mustermann", c.fullName)
        val text = VCard.create(
            Contact(uid = "u1").copy(
                fullName = c.fullName, givenName = c.givenName, familyName = c.familyName, emails = c.emails,
                phones = c.phones, birthday = c.birthday, note = c.note, addresses = c.addresses,
            ),
            photo = "data:image/png;base64,iVBORw0K",
        ).serialize()
        assertTrue(text, text.contains("BDAY;X-APPLE-OMIT-YEAR=1604:1604-03-04"))
        assertTrue(text, text.contains("item1.TEL:040 1"))
        assertTrue(text, text.contains("item1.X-ABLABEL:Ferienhaus"))
        assertTrue(text, text.contains("PHOTO;ENCODING=b;TYPE=PNG:iVBORw0K"))
        assertTrue(text.split("\r\n").all { it.toByteArray().size <= 76 })
        val back = VCard.extract(VCard.normalizeImport(VCard.parseCards(text).single()))
        assertEquals(c.copy(photo = null), back.toInput().normalizedInput().copy(addressBookId = c.addressBookId))
        assertEquals("u1", back.uid)
        assertEquals("data:image/png;base64,iVBORw0K", back.photo)
    }

    @Test
    fun vCardCategories() {
        val card = VCard.parseCards(
            "BEGIN:VCARD\nVERSION:3.0\nFN:A\nCATEGORIES:Familie,Freunde\\, alt\nCATEGORIES:familie,Arbeit\nEND:VCARD\n",
        ).single()
        val c = VCard.extract(card)
        assertEquals(listOf("Familie", "Freunde, alt", "Arbeit"), c.categories)
        val text = VCard.create(c.copy(uid = "u1"), photo = null).serialize()
        assertTrue(text, text.contains("CATEGORIES:Familie,Freunde\\, alt,Arbeit"))
        assertEquals(c.categories, VCard.extract(VCard.parseCards(text).single()).categories)

        val input = c.toInput().copy(categories = listOf(" Verein ", "verein", "")).normalizedInput()
        assertEquals(listOf("Verein"), input.categories)

        val v21 = "BEGIN:VCARD\r\nVERSION:2.1\r\nN:A;B\r\nCATEGORIES:Familie,Arbeit\r\nEND:VCARD\r\n"
        assertEquals(listOf("Familie", "Arbeit"), VCard.extract(VCard.normalizeImport(VCard.parseCards(v21).single())).categories)
    }

    @Test
    fun appleBirthdayWithoutYear() {
        val card = VCard.parseCards("BEGIN:VCARD\nVERSION:3.0\nFN:A\nBDAY;X-APPLE-OMIT-YEAR=1604:1604-05-12\nEND:VCARD\n").single()
        assertEquals("--05-12", VCard.extract(card).birthday)
    }

    // ---------- Birthdays ----------

    @Test
    fun parsesBirthdays() {
        assertEquals(LocalDate.of(1964, 8, 12) to 1964, Birthdays.parse("1964-08-12"))
        assertEquals(LocalDate.of(2000, 3, 4) to null, Birthdays.parse("--03-04"))
        assertEquals("FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1", Birthdays.rruleFor(Birthdays.parse("--02-29")!!.first))
        assertNull(Birthdays.parse(""))
        assertNull(Birthdays.parse("1964-02-30"))
    }

    @Test
    fun birthdayCalendarFollowsContacts() {
        var n = 0
        val newId = { "id${++n}" }
        val own = CalEvent(id = "e1", calendarId = "c1", title = "Termin", start = "2026-01-01T10:00:00Z", end = "2026-01-01T11:00:00Z")
        val start = OfflineData(
            calendars = listOf(Calendar("c1", "Persönlich")),
            events = listOf(own),
            contacts = listOf(Contact(id = "k1", fullName = "Erika", birthday = "1964-08-12"), Contact(id = "k2", fullName = "Ohne")),
        )
        val d = Birthdays.sync(start, newId, "2026-01-01T00:00:00Z")
        val cal = d.calendars!!.single { it.isBirthdays }
        val ev = d.events!!.single { it.contactId == "k1" }
        assertEquals(cal.id, ev.calendarId)
        assertEquals("🎂 Erika", ev.title)
        assertEquals("1964-08-12T00:00:00Z", ev.start)
        assertTrue(ev.readOnly && ev.allDay)
        assertEquals("Geboren am 12.08.1964", ev.description)
        assertTrue(own in d.events!!)
        // Age in the title of the occurrence
        val occ = d.occurrences(Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-09-01T00:00:00Z"))!!
        assertEquals("🎂 Erika (62)", occ.single().title)

        // Birthday removed: event gone, calendar stays; the ID stays stable across changes
        val changed = Birthdays.sync(d.copy(contacts = listOf(Contact(id = "k1", fullName = "Erika M.", birthday = "1964-08-13"))), newId, "x")
        assertEquals(ev.id, changed.events!!.single { it.contactId == "k1" }.id)
        val removed = Birthdays.sync(d.copy(contacts = listOf(Contact(id = "k1", fullName = "Erika"))), newId, "x")
        assertTrue(removed.events!!.none { it.contactId != null })
        assertTrue(removed.calendars!!.any { it.isBirthdays })

        // Without birthdays no calendar is created
        assertEquals(start.copy(contacts = emptyList()), Birthdays.sync(start.copy(contacts = emptyList()), newId, "x"))
    }
}
