package de.dave.synchaven.util

import de.dave.synchaven.data.api.AppJson
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.data.offline.PendingOp
import de.dave.synchaven.data.offline.toLocalEvent
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemindersTest {
    private fun event(start: String, end: String, allDay: Boolean = false, rrule: String? = null, reminders: List<Int>? = null) =
        CalEvent(id = "e", calendarId = "c", title = "T", start = start, end = end, allDay = allDay,
            timezone = "Europe/Berlin", rrule = rrule, reminders = reminders)

    private fun input(reminders: kotlinx.serialization.json.JsonElement? = null) = EventInput(
        calendarId = "c", title = "T", description = "", location = "", start = "2026-11-24T13:00:00Z",
        end = "2026-11-24T14:00:00Z", allDay = false, timezone = "UTC", rrule = null, reminders = reminders,
    )

    private val utc = ZoneOffset.UTC
    private fun t(s: String) = Instant.parse(s)

    @Test
    fun timedReminderFiresOnce() {
        val ev = event("2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")
        val at = t("2026-11-24T12:50:00Z")
        val due = Reminders.due(ev, listOf(10), utc, at.minusSeconds(30), at)
        assertEquals(1, due.size)
        assertEquals(at, due[0].at)
        assertTrue(Reminders.due(ev, listOf(10), utc, at, at.plusSeconds(30)).isEmpty())
    }

    @Test
    fun recurringAndAllDayUseLocalTime() {
        val ev = event("2026-11-20T08:00:00Z", "2026-11-20T09:00:00Z", rrule = "FREQ=DAILY")
        val at = t("2026-11-25T08:00:00Z")
        val due = Reminders.due(ev, listOf(1440), utc, at.minusSeconds(60), at)
        assertEquals(listOf(t("2026-11-26T08:00:00Z")), due.map { it.start })

        // All-day on Dec 24, the day before at 18:00 Berlin time = 17:00 UTC; on the day itself 09:00 = 08:00 UTC
        val day = event("2026-12-24T00:00:00Z", "2026-12-25T00:00:00Z", allDay = true)
        val berlin = ZoneId.of("Europe/Berlin")
        val eve = t("2026-12-23T17:00:00Z")
        assertEquals(1, Reminders.due(day, listOf(360), berlin, eve.minusSeconds(30), eve).size)
        val morning = t("2026-12-24T08:00:00Z")
        assertEquals(1, Reminders.due(day, listOf(-540), berlin, morning.minusSeconds(30), morning).size)
        assertTrue(Reminders.due(day, listOf(-540), utc, morning.minusSeconds(30), morning).isEmpty())
    }

    @Test
    fun nextUsesCalendarDefaults() {
        val cal = Calendar(id = "c", name = "K", defaultReminders = listOf(30), defaultAllDayReminders = listOf(-540))
        val ev = event("2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z")
        assertEquals(t("2026-11-24T12:30:00Z"), Reminders.next(listOf(ev), listOf(cal), utc, t("2026-11-24T00:00:00Z"), t("2026-11-25T00:00:00Z")))
        // Own reminders (even empty) take precedence
        assertNull(Reminders.next(listOf(ev.copy(reminders = emptyList())), listOf(cal), utc, t("2026-11-24T00:00:00Z"), t("2026-11-25T00:00:00Z")))
    }

    @Test
    fun splitAllDay() {
        assertEquals(Triple(0, 9, 0), Reminders.splitAllDay(-540))
        assertEquals(Triple(1, 9, 0), Reminders.splitAllDay(900))
        assertEquals(Triple(0, 0, 0), Reminders.splitAllDay(0))
        assertEquals(Triple(2, 18, 30), Reminders.splitAllDay(2 * 1440 - (18 * 60 + 30)))
    }

    @Test
    fun validation() {
        assertEquals(listOf(10, 30), Reminders.validate(listOf(30, 10, 10), false))
        assertFalse(Reminders.isValid(-5, false))
        assertTrue(Reminders.isValid(-540, true))
        assertFalse(Reminders.isValid(Reminders.MAX_BEFORE + 1, true))
    }

    /** "Field missing" (unchanged) and `null` (calendar default) must be preserved on the way to the server and in the cache. */
    @Test
    fun reminderFieldKeepsAbsentNullAndList() {
        val absent = AppJson.encodeToString(EventInput.serializer(), input())
        assertFalse(absent.contains("reminders"))
        val reset = AppJson.encodeToString(EventInput.serializer(), input(remindersField(null)))
        assertTrue(reset.contains("\"reminders\":null"))
        val own = AppJson.encodeToString(EventInput.serializer(), input(remindersField(listOf(10, 60))))
        assertTrue(own.contains("\"reminders\":[10,60]"))

        assertEquals(listOf(5), AppJson.decodeFromString(EventInput.serializer(), absent).applyReminders(listOf(5)))
        assertNull(AppJson.decodeFromString(EventInput.serializer(), reset).applyReminders(listOf(5)))
        assertEquals(listOf(10, 60), AppJson.decodeFromString(EventInput.serializer(), own).applyReminders(listOf(5)))
    }

    @Test
    fun offlineUpdateKeepsOrReplacesReminders() {
        val base = event("2026-11-24T13:00:00Z", "2026-11-24T14:00:00Z", reminders = listOf(15))
        assertEquals(listOf(15), input().toLocalEvent("e", base).reminders)
        assertNull(input(remindersField(null)).toLocalEvent("e", base).reminders)
        val data = OfflineData(events = listOf(base)).apply(PendingOp.UpdateEvent("e", input(remindersField(listOf(0)))))
        assertEquals(listOf(0), data.events!!.single().reminders)
        // Stored queue: `null` stays "calendar default"
        val json = AppJson.encodeToString(OfflineData.serializer(), OfflineData(outbox = listOf(PendingOp.UpdateEvent("e", input(remindersField(null))))))
        val back = AppJson.decodeFromString(OfflineData.serializer(), json).outbox.single() as PendingOp.UpdateEvent
        assertNull(back.input.applyReminders(listOf(15)))
    }

    /** Local mode: empty default reminders of an early test version are replaced once, and not afterwards. */
    @Test
    fun localMigrationSetsDefaultOnce() {
        val json = """{"calendars":[{"id":"c","name":"K","default_reminders":[],"default_all_day_reminders":[]},
            {"id":"d","name":"L","default_reminders":[30],"default_all_day_reminders":[]}]}"""
        val old = AppJson.decodeFromString(OfflineData.serializer(), json)
        val migrated = old.migrateLocal()
        assertEquals(listOf(15), migrated.calendars!![0].defaultReminders)
        assertEquals(listOf(-540), migrated.calendars!![0].defaultAllDayReminders)
        assertEquals(listOf(30), migrated.calendars!![1].defaultReminders)
        // Defaults deliberately cleared afterwards stay empty
        val cleared = migrated.copy(calendars = migrated.calendars!!.map { it.copy(defaultReminders = emptyList(), defaultAllDayReminders = emptyList()) })
        assertEquals(emptyList<Int>(), cleared.migrateLocal().calendars!![0].defaultReminders)
        // Data without the fields (from before reminders) gets the preset when read
        val before = AppJson.decodeFromString(OfflineData.serializer(), """{"calendars":[{"id":"c","name":"K"}]}""")
        assertEquals(listOf(15), before.calendars!![0].defaultReminders)
    }

    @Test
    fun taskReminders() {
        fun task(due: String?, allDay: Boolean, completed: Boolean = false) =
            Task(id = "t", listId = "l", title = "A", due = due, allDay = allDay, completed = completed)
        val timed = task("2026-11-24T13:00:00Z", allDay = false)
        val at = t("2026-11-24T13:00:00Z")
        assertEquals(at, Reminders.taskReminderAt(timed, utc))
        assertEquals(1, Reminders.dueTasks(listOf(timed), utc, at.minusSeconds(30), at).size)
        assertTrue(Reminders.dueTasks(listOf(timed), utc, at, at.plusSeconds(30)).isEmpty())
        assertNull(Reminders.taskReminderAt(task("2026-11-24T13:00:00Z", allDay = false, completed = true), utc))
        assertNull(Reminders.taskReminderAt(task(null, allDay = true), utc))

        // All-day on Dec 24: 09:00 Berlin time = 08:00 UTC
        val allDay = task("2026-12-24T00:00:00Z", allDay = true)
        assertEquals(t("2026-12-24T08:00:00Z"), Reminders.taskReminderAt(allDay, ZoneId.of("Europe/Berlin")))
        assertEquals(t("2026-12-24T08:00:00Z"),
            Reminders.nextTask(listOf(timed, allDay), ZoneId.of("Europe/Berlin"), t("2026-12-01T00:00:00Z"), t("2026-12-31T00:00:00Z")))
    }
}
