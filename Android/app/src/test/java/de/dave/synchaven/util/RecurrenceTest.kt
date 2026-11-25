package de.dave.synchaven.util

import de.dave.synchaven.LocaleRule
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import java.time.DayOfWeek
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

class RecurrenceTest {
    @get:Rule val locale = LocaleRule(Locale.GERMANY)

    private val monday = LocalDate.of(2026, 11, 23)

    @Test
    fun weeklyRoundTrip() {
        val rule = "FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,WE;COUNT=10"
        val form = parseRRule(rule, monday)
        assertEquals(Freq.WEEKLY, form.freq)
        assertEquals(2, form.interval)
        assertEquals(setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), form.byDay)
        assertEquals(EndMode.COUNT, form.endMode)
        assertEquals(rule, buildRRule(form, monday))
    }

    @Test
    fun monthlyByWeekday() {
        // Nov 30, 2026 is the last Monday in November
        val last = LocalDate.of(2026, 11, 30)
        assertEquals(-1, weekdayOrdinal(last))
        val form = RecurrenceForm(freq = Freq.MONTHLY, monthlyMode = MonthlyMode.WEEKDAY)
        assertEquals("FREQ=MONTHLY;BYDAY=-1MO", buildRRule(form, last))
        val on = UiText.Res(R.string.rrule_monthly_on_weekday, listOf(UiText.Res(R.string.ordinal_last), "Montag"))
        assertEquals(UiText.Plural(R.plurals.rrule_monthly, 1, listOf(1, on)), describeRRule("FREQ=MONTHLY;BYDAY=-1MO", last))
    }

    @Test
    fun untilDate() {
        val form = RecurrenceForm(freq = Freq.DAILY, endMode = EndMode.UNTIL, until = LocalDate.of(2026, 12, 31))
        assertEquals("FREQ=DAILY;UNTIL=20261231", buildRRule(form, monday))
        val daily = UiText.Plural(R.plurals.rrule_daily, 1, listOf(1))
        assertEquals(UiText.Res(R.string.rrule_until, listOf(daily, "31.12.2026")), describeRRule("FREQ=DAILY;UNTIL=20261231", monday))
        val every3 = UiText.Plural(R.plurals.rrule_daily, 3, listOf(3))
        assertEquals(UiText.Res(R.string.rrule_count, listOf(every3, "5")), describeRRule("FREQ=DAILY;INTERVAL=3;COUNT=5", monday))
    }

    @Test
    fun unsupportedPartsBecomeCustom() {
        val form = parseRRule("FREQ=WEEKLY;BYSETPOS=1;BYDAY=MO", monday)
        assertEquals(Freq.CUSTOM, form.freq)
        assertEquals("FREQ=WEEKLY;BYSETPOS=1;BYDAY=MO", buildRRule(form, monday))
    }

    @Test
    fun noneHasNoRule() {
        assertNull(buildRRule(RecurrenceForm(), monday))
        val days = UiText.Join(listOf(UiText.Plain("Mo"), UiText.Plain("Mi")))
        assertEquals(UiText.Plural(R.plurals.rrule_weekly, 1, listOf(1, days)), describeRRule("FREQ=WEEKLY;BYDAY=MO,WE", monday))
        assertEquals(UiText.Res(R.string.rrule_raw, listOf("FREQ=HOURLY")), describeRRule("FREQ=HOURLY", monday))
    }
}
