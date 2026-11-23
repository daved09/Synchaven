package de.dave.synchaven.sync

import android.provider.ContactsContract.CommonDataKinds.BaseTypes
import android.provider.ContactsContract.CommonDataKinds.Email
import android.provider.ContactsContract.CommonDataKinds.Phone
import de.dave.synchaven.data.model.CalEvent
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncMappingTest {
    private fun event(
        start: String, end: String, allDay: Boolean = false, rrule: String? = null, exdates: List<String> = emptyList(),
    ) = CalEvent(
        id = "e1", calendarId = "c1", title = "Jour fixe", start = start, end = end, allDay = allDay,
        timezone = "Europe/Berlin", rrule = rrule, exdates = exdates,
    )

    @Test
    fun singleEventUsesDtEnd() {
        val t = event("2026-11-30T08:00:00Z", "2026-11-30T09:00:00Z").toAndroidTimes()
        assertEquals(Instant.parse("2026-11-30T08:00:00Z").toEpochMilli(), t.dtStart)
        assertEquals(Instant.parse("2026-11-30T09:00:00Z").toEpochMilli(), t.dtEnd)
        assertNull(t.duration)
        assertNull(t.rrule)
        assertEquals("Europe/Berlin", t.timezone)
    }

    @Test
    fun seriesUsesDurationAndExdate() {
        val t = event(
            "2026-11-30T08:00:00Z", "2026-11-30T09:30:00Z", rrule = "FREQ=WEEKLY;BYDAY=MO",
            exdates = listOf("2026-12-14T08:00:00Z", "2026-12-07T08:00:00Z"),
        ).toAndroidTimes()
        assertNull(t.dtEnd)
        assertEquals("P5400S", t.duration)
        assertEquals("FREQ=WEEKLY;BYDAY=MO", t.rrule)
        assertEquals("20261214T080000Z,20261207T080000Z", t.exdate)
    }

    @Test
    fun allDaySeriesInUtcWithDays() {
        val t = event("2026-12-24T00:00:00Z", "2026-12-26T00:00:00Z", allDay = true, rrule = "FREQ=YEARLY").toAndroidTimes()
        assertEquals("UTC", t.timezone)
        assertEquals("P2D", t.duration)
    }

    @Test
    fun exdatesParseAllFormats() {
        val start = Instant.parse("2026-11-30T08:00:00Z").toEpochMilli() // 09:00 in Berlin
        val parsed = parseExdates(
            "20261207T080000Z,20261214T080000Z\nEurope/Berlin;20261221T090000\n20261228",
            "Europe/Berlin", start, allDay = false,
        )
        assertEquals(
            listOf("2026-12-07T08:00:00Z", "2026-12-14T08:00:00Z", "2026-12-21T08:00:00Z", "2026-12-28T08:00:00Z")
                .map(Instant::parse),
            parsed,
        )
        assertEquals(emptyList<Instant>(), parseExdates(null, "UTC", start, false))
    }

    @Test
    fun exdatesRoundTrip() {
        val dates = listOf(Instant.parse("2026-12-07T08:00:00Z"), Instant.parse("2026-12-14T08:00:00Z"))
        assertEquals(dates, parseExdates(formatExdates(dates), "Europe/Berlin", dates[0].toEpochMilli(), false))
    }

    @Test
    fun durations() {
        assertEquals(3_600_000L, parseDurationMillis("P3600S"))
        assertEquals(5_400_000L, parseDurationMillis("PT1H30M"))
        assertEquals(86_400_000L, parseDurationMillis("P1D"))
        assertEquals(14 * 86_400_000L, parseDurationMillis("P2W"))
        assertNull(parseDurationMillis("1 Stunde"))
        val start = 1_000_000L
        assertEquals(start + 3_600_000, androidEventEnd(start, null, "PT1H", false))
        assertEquals(start + 86_400_000, androidEventEnd(start, null, null, true))
        assertEquals(start + 5, androidEventEnd(start, start + 5, "PT1H", false))
    }

    @Test
    fun apiTimeForAllDayIsUtcMidnight() {
        val ms = Instant.parse("2026-12-24T00:00:00Z").toEpochMilli()
        assertEquals("2026-12-24T00:00:00Z", apiTime(ms, allDay = true))
        assertEquals("2026-12-24T10:15:00Z", apiTime(ms + 36_900_000, allDay = false))
    }

    @Test
    fun hashChangesWithContent() {
        val a = event("2026-11-30T08:00:00Z", "2026-11-30T09:00:00Z")
        assertEquals(a.syncHash(), a.copy().syncHash())
        assertNotEquals(a.syncHash(), a.copy(title = "Anders").syncHash())
        assertNotEquals(a.syncHash(), a.copy(exdates = listOf("2026-12-07T08:00:00Z")).syncHash())
    }

    @Test
    fun colors() {
        assertEquals(0xFF0B57D0.toInt(), parseColor("#0B57D0"))
        assertEquals(1, parseColor("kaputt", fallback = 1))
    }

    @Test
    fun phoneTypes() {
        val label: (Int) -> String = { "Hauptnummer" }
        for (t in listOf("mobile", "home", "work", "fax", "pager", "other")) {
            val a = phoneTypeToAndroid(t)
            assertEquals(t, phoneTypeFromAndroid(a.type, a.label, label))
        }
        assertEquals(AndroidType(BaseTypes.TYPE_CUSTOM, "Ferienhaus"), phoneTypeToAndroid("Ferienhaus"))
        assertEquals("Ferienhaus", phoneTypeFromAndroid(BaseTypes.TYPE_CUSTOM, "Ferienhaus", label))
        assertEquals("fax", phoneTypeFromAndroid(Phone.TYPE_FAX_WORK, null, label))
        assertEquals("Hauptnummer", phoneTypeFromAndroid(Phone.TYPE_MAIN, null, label))
        assertEquals("other", phoneTypeFromAndroid(BaseTypes.TYPE_CUSTOM, " ", label))
    }

    @Test
    fun emailAndOtherTypes() {
        for (t in listOf("home", "work", "other", "Verein")) {
            val e = emailTypeToAndroid(t)
            assertEquals(t, emailTypeFromAndroid(e.type, e.label) { "x" })
            val p = postalTypeToAndroid(t)
            assertEquals(t, postalTypeFromAndroid(p.type, p.label))
            val w = websiteTypeToAndroid(t)
            assertEquals(t, websiteTypeFromAndroid(w.type, w.label))
        }
        assertEquals("Mobil", emailTypeFromAndroid(Email.TYPE_MOBILE, null) { "Mobil" })
    }

    @Test
    fun birthdays() {
        assertEquals("1964-08-12", normalizeBirthday("1964-08-12"))
        assertEquals("1964-08-12", normalizeBirthday("19640812"))
        assertEquals("1964-08-12", normalizeBirthday("1964-08-12T00:00:00Z"))
        assertEquals("--08-12", normalizeBirthday("--08-12"))
        assertEquals("--08-12", normalizeBirthday("--0812"))
        assertEquals("--08-12", normalizeBirthday("1604-08-12"))
        assertEquals("", normalizeBirthday("12. August"))
        assertEquals("", normalizeBirthday(null))
    }

    @Test
    fun addressFormatting() {
        assertEquals(
            "Heidestr. 17\n51147 Köln\nDeutschland",
            formatAddress("Heidestr. 17", "", "", "51147", "Köln", "", "Deutschland"),
        )
    }
}
