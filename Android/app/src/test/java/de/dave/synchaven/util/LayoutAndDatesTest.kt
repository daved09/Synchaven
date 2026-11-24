package de.dave.synchaven.util

import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.store.ViewMode
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutAndDatesTest {
    private fun ev(id: String, start: LocalDateTime, end: LocalDateTime, allDay: Boolean = false) = ViewEvent(
        Occurrence(id = id, eventId = id, calendarId = "c", title = id, start = "", end = "", allDay = allDay),
        start, end, "#6750A4",
    )

    @Test
    fun monthRangeCoversFullWeeks() {
        // November 2026 starts on a Sunday → grid from Monday, Oct 26
        val r = viewRange(ViewMode.MONTH, LocalDate.of(2026, 11, 22), DayOfWeek.MONDAY)
        assertEquals(LocalDate.of(2026, 10, 26), r.start)
        assertEquals(0, r.days.size % 7)
        assertTrue(r.end > LocalDate.of(2026, 11, 30))
    }

    @Test
    fun titles() {
        val date = LocalDate.of(2026, 11, 30)
        val week = viewRange(ViewMode.WEEK, date, DayOfWeek.MONDAY)
        // Week 49 runs from Nov 30 to Dec 6 – two months in the title
        assertEquals(
            UiText.Res(R.string.view_title_week, listOf(UiText.Res(R.string.month_range, listOf("Nov.", "Dez. 2026")), 49)),
            viewTitle(ViewMode.WEEK, date, week),
        )
        assertEquals(UiText.Plain("November 2026"), viewTitle(ViewMode.MONTH, date, viewRange(ViewMode.MONTH, date, DayOfWeek.MONDAY)))
        val start = date.atTime(9, 0)
        assertEquals(
            UiText.Res(R.string.span_same_day, listOf("Mo., 30. Nov. 2026", "09:00", "10:00")),
            describeSpan(start, start.plusHours(1), allDay = false),
        )
    }

    @Test
    fun allDayEndIsExclusive() {
        val e = ev("a", LocalDate.of(2026, 12, 24).atStartOfDay(), LocalDate.of(2026, 12, 25).atStartOfDay(), allDay = true)
        assertEquals(LocalDate.of(2026, 12, 24), e.lastDay)
        assertTrue(e.touches(LocalDate.of(2026, 12, 24)))
        assertFalse(e.touches(LocalDate.of(2026, 12, 25)))
        assertEquals("2026-12-24T00:00:00Z", localToAllDay(LocalDate.of(2026, 12, 24)))
        assertEquals(LocalDate.of(2026, 12, 24), allDayToLocal("2026-12-24T00:00:00Z"))
    }

    @Test
    fun overlappingEventsGetColumns() {
        val day = LocalDate.of(2026, 11, 23)
        val items = layoutDay(
            listOf(
                ev("a", day.atTime(9, 0), day.atTime(10, 0)),
                ev("b", day.atTime(9, 30), day.atTime(11, 0)),
                ev("c", day.atTime(12, 0), day.atTime(13, 0)),
            ),
            day,
        ).associateBy { it.ev.id }
        assertEquals(2, items.getValue("a").cols)
        assertEquals(1, items.getValue("b").col)
        assertEquals(1, items.getValue("c").cols)
        assertEquals(9 * 60, items.getValue("a").top)
    }

    @Test
    fun rowLanesAndOverflow() {
        val mon = LocalDate.of(2026, 11, 23)
        val events = (0 until 4).map { ev("e$it", mon.atTime(8 + it, 0), mon.atTime(9 + it, 0)) }
        val items = layoutRow(events, mon, 7)
        assertEquals(listOf(0, 1, 2, 3), items.map { it.lane })
        val clamped = clampRow(items, 7, 3)
        // Two visible, the last lane makes room for "+2"
        assertEquals(2, clamped.visible.size)
        assertEquals(2, clamped.more[0])
    }
}
