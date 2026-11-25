package de.dave.synchaven.util

import de.dave.synchaven.LocaleRule
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskList
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Tasks in the calendar views and the task list */
class TasksTest {
    @get:Rule val locale = LocaleRule(Locale.GERMANY)

    private val berlin = ZoneId.of("Europe/Berlin")

    @Test
    fun dueForApiFollowsTheEventTimeModel() {
        assertNull(taskDue(null, LocalTime.of(9, 0), berlin))
        assertEquals("2026-11-24T00:00:00Z", taskDue(LocalDate.of(2026, 11, 24), null, berlin))
        // 09:30 in Berlin (winter time) = 08:30 UTC
        assertEquals("2026-11-24T08:30:00Z", taskDue(LocalDate.of(2026, 11, 24), LocalTime.of(9, 30), berlin))
    }

    @Test
    fun allDayTaskIsOneDayInTheCalendar() {
        // All-day: the day stays the same, regardless of the device's time zone
        val ev = Task("t1", "l1", "Steuer", due = "2026-11-25T00:00:00Z", allDay = true)
            .toViewEvent("#0B57D0", ZoneId.of("America/New_York"))!!
        assertEquals(LocalDateTime.of(2026, 11, 25, 0, 0), ev.start)
        assertEquals(LocalDateTime.of(2026, 11, 26, 0, 0), ev.end)
        assertTrue(ev.isBar && ev.isTask && ev.allDay)
        assertEquals(LocalDate.of(2026, 11, 25), ev.lastDay)
        assertEquals("task:t1", ev.id)
        assertEquals("t1", ev.occ.eventId)
        assertEquals("#0B57D0", ev.color)
    }

    @Test
    fun timedTaskIsShortBlockInLocalTime() {
        val ev = Task("t1", "l1", "Anrufen", due = "2026-11-24T08:30:00Z", allDay = false, completed = true)
            .toViewEvent("#1A73E8", berlin)!!
        assertEquals(LocalDateTime.of(2026, 11, 24, 9, 30), ev.start)
        assertEquals(LocalDateTime.of(2026, 11, 24, 10, 0), ev.end)
        assertFalse(ev.isBar)
        assertTrue(ev.completed)
        assertEquals("2026-11-24T09:00:00Z", ev.occ.end)
    }

    @Test
    fun calendarShowsOnlyDatedTasksOfVisibleLists() {
        val lists = listOf(TaskList("l1", "A", color = "#111111"), TaskList("l2", "B", visible = false))
        val tasks = listOf(
            Task("t1", "l1", "fällig", due = "2026-11-25T00:00:00Z"),
            Task("t2", "l1", "ohne Datum"),
            Task("t3", "l2", "ausgeblendet", due = "2026-11-25T00:00:00Z"),
            Task("t4", "gone", "Liste fehlt", due = "2026-11-25T00:00:00Z"),
        )
        val events = calendarTasks(lists, tasks, berlin)
        assertEquals(listOf("t1"), events.map { it.task!!.id })
        assertEquals("#111111", events.single().color)
    }

    @Test
    fun listsAreSortedLikeTheWeb() {
        val tasks = listOf(
            Task("a", "l1", "ohne Datum", createdAt = "1"),
            Task("b", "l1", "später", due = "2026-12-01T00:00:00Z"),
            Task("c", "l1", "früher", due = "2026-11-24T08:00:00Z", allDay = false),
            Task("d", "l1", "erledigt alt", completed = true, completedAt = "2026-11-01T00:00:00Z"),
            Task("e", "l1", "erledigt neu", completed = true, completedAt = "2026-11-20T00:00:00Z"),
            Task("f", "l2", "andere Liste"),
        )
        assertEquals(listOf("c", "b", "a"), openTasks(tasks, "l1").map { it.id })
        assertEquals(listOf("e", "d"), completedTasks(tasks, "l1").map { it.id })
    }

    @Test
    fun overdueAndDueText() {
        val now = LocalDateTime.of(2026, 11, 23, 12, 0)
        val today = now.toLocalDate()
        val yesterday = Task("t", "l", "x", due = "2026-11-22T00:00:00Z")
        val todayAllDay = Task("t", "l", "x", due = "2026-11-23T00:00:00Z")
        // 10:00 Berlin = 09:00 UTC, so before 12:00
        val todayMorning = Task("t", "l", "x", due = "2026-11-23T09:00:00Z", allDay = false)
        assertTrue(yesterday.isOverdue(now, berlin))
        assertFalse(todayAllDay.isOverdue(now, berlin))
        assertTrue(todayMorning.isOverdue(now, berlin))
        assertFalse(yesterday.copy(completed = true).isOverdue(now, berlin))
        assertFalse(Task("t", "l", "x").isOverdue(now, berlin))

        assertEquals(UiText.Res(R.string.task_due_yesterday), yesterday.dueText(today, berlin))
        assertEquals(UiText.Res(R.string.task_due_today), todayAllDay.dueText(today, berlin))
        assertEquals(
            UiText.Res(R.string.task_due_with_time, listOf(UiText.Res(R.string.task_due_today), "10:00")),
            todayMorning.dueText(today, berlin),
        )
        assertEquals(UiText.Plain("Fr., 4. Dez."), Task("t", "l", "x", due = "2026-12-04T00:00:00Z").dueText(today, berlin))
        assertNull(Task("t", "l", "x").dueText(today, berlin))
    }
}
