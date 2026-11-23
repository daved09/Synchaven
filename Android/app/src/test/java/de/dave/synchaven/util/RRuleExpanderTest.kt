package de.dave.synchaven.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

class RRuleExpanderTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun utc(s: String) = Instant.parse(s)

    private fun expand(
        start: String,
        end: String,
        rule: String?,
        from: String,
        to: String,
        zone: ZoneId = ZoneOffset.UTC,
        allDay: Boolean = false,
        exdates: Set<Instant> = emptySet(),
    ) = RRuleExpander.occurrences(utc(start), utc(end), zone, allDay, rule, exdates, utc(from), utc(to))

    @Test
    fun weeklyKeepsLocalTimeAcrossDst() {
        // 09:00 Berlin (Winterzeit = 08:00 UTC), nach der Umstellung am 29.03. liegt 09:00 bei 07:00 UTC
        val occ = expand("2026-03-16T08:00:00Z", "2026-03-16T09:00:00Z", "FREQ=WEEKLY;COUNT=4",
            "2026-03-16T08:00:00Z", "2026-04-15T00:00:00Z", zone = berlin)
        assertEquals(4, occ.size)
        assertEquals(utc("2026-03-30T07:00:00Z"), occ[2])
    }

    @Test
    fun exdatesAndRange() {
        val occ = expand("2026-01-01T10:00:00Z", "2026-01-01T12:00:00Z", "FREQ=DAILY",
            "2026-01-02T11:00:00Z", "2026-01-05T00:00:00Z", exdates = setOf(utc("2026-01-03T10:00:00Z")))
        // 02.01. (läuft noch bis 12:00), 03.01. ausgenommen, 04.01.
        assertEquals(listOf(utc("2026-01-02T10:00:00Z"), utc("2026-01-04T10:00:00Z")), occ)
    }

    @Test
    fun countIsCountedFromStartNotFromRange() {
        val occ = expand("2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "FREQ=DAILY;COUNT=5",
            "2026-01-04T00:00:00Z", "2026-02-01T00:00:00Z")
        assertEquals(listOf(utc("2026-01-04T10:00:00Z"), utc("2026-01-05T10:00:00Z")), occ)
    }

    @Test
    fun untilDateIncludesWholeDay() {
        val occ = expand("2026-01-01T08:00:00Z", "2026-01-01T09:00:00Z", "FREQ=DAILY;UNTIL=20260105T225959Z",
            "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", zone = berlin)
        assertEquals(5, occ.size)
        val local = expand("2026-01-01T08:00:00Z", "2026-01-01T09:00:00Z", "FREQ=DAILY;UNTIL=20260105",
            "2026-01-01T00:00:00Z", "2026-02-01T00:00:00Z", zone = berlin)
        assertEquals(5, local.size)
    }

    @Test
    fun weeklyByDayWithInterval() {
        // Montag, 23.11.2026; alle zwei Wochen Mo und Mi
        val occ = expand("2026-11-23T17:00:00Z", "2026-11-23T18:00:00Z", "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE",
            "2026-11-23T00:00:00Z", "2026-12-15T00:00:00Z")
        assertEquals(
            listOf("2026-11-23", "2026-11-25", "2026-12-07", "2026-12-09"),
            occ.map { it.atOffset(ZoneOffset.UTC).toLocalDate().toString() },
        )
    }

    @Test
    fun monthlyLastWeekdayAndMonthDay() {
        val last = expand("2026-11-30T09:00:00Z", "2026-11-30T10:00:00Z", "FREQ=MONTHLY;BYDAY=-1MO",
            "2026-11-01T00:00:00Z", "2027-02-01T00:00:00Z")
        assertEquals(listOf("2026-11-30", "2026-12-28", "2027-01-25"), last.map { it.toString().take(10) })

        // 31. nur in Monaten, die einen haben
        val day31 = expand("2026-01-31T09:00:00Z", "2026-01-31T10:00:00Z", "FREQ=MONTHLY",
            "2026-01-01T00:00:00Z", "2026-06-01T00:00:00Z")
        assertEquals(listOf("2026-01-31", "2026-03-31", "2026-05-31"), day31.map { it.toString().take(10) })
    }

    @Test
    fun bySetPosLastWorkday() {
        val occ = expand("2026-01-30T09:00:00Z", "2026-01-30T10:00:00Z", "FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1",
            "2026-01-01T00:00:00Z", "2026-04-01T00:00:00Z")
        assertEquals(listOf("2026-01-30", "2026-02-27", "2026-03-31"), occ.map { it.toString().take(10) })
    }

    @Test
    fun birthdayOnLeapDay() {
        // Geburtstagskalender: 29.02. → letzter Tag im Februar
        val occ = expand("2024-02-29T00:00:00Z", "2024-03-01T00:00:00Z", "FREQ=YEARLY;BYMONTH=2;BYMONTHDAY=-1",
            "2026-01-01T00:00:00Z", "2029-01-01T00:00:00Z", allDay = true)
        assertEquals(listOf("2026-02-28", "2027-02-28", "2028-02-29"), occ.map { it.toString().take(10) })
    }

    @Test
    fun yearlyAllDayStaysOnDate() {
        val occ = expand("1990-05-17T00:00:00Z", "1990-05-18T00:00:00Z", "FREQ=YEARLY",
            "2026-05-01T00:00:00Z", "2026-06-01T00:00:00Z", zone = berlin, allDay = true)
        assertEquals(listOf(utc("2026-05-17T00:00:00Z")), occ)
    }

    @Test
    fun unsupportedRuleYieldsFirstOccurrenceOnly() {
        val occ = expand("2026-01-01T10:00:00Z", "2026-01-01T11:00:00Z", "FREQ=HOURLY",
            "2026-01-01T00:00:00Z", "2026-01-03T00:00:00Z")
        assertEquals(listOf(utc("2026-01-01T10:00:00Z")), occ)
    }

    @Test
    fun singleEventOverlap() {
        assertEquals(1, expand("2026-01-01T23:00:00Z", "2026-01-02T01:00:00Z", null,
            "2026-01-02T00:00:00Z", "2026-01-03T00:00:00Z").size)
        assertEquals(0, expand("2026-01-01T22:00:00Z", "2026-01-02T00:00:00Z", null,
            "2026-01-02T00:00:00Z", "2026-01-03T00:00:00Z").size)
    }

    @Test
    fun longRunningSeriesIsFast() {
        val start = LocalDate.of(2000, 1, 1)
        val occ = expand("${start}T06:00:00Z", "${start}T07:00:00Z", "FREQ=DAILY",
            "2026-11-01T00:00:00Z", "2026-12-01T00:00:00Z")
        assertEquals(30, occ.size)
    }
}
