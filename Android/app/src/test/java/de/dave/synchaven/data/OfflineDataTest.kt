package de.dave.synchaven.data

import de.dave.synchaven.data.api.AppJson
import de.dave.synchaven.data.model.AddressBook
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.CalendarKind
import de.dave.synchaven.data.model.Contact
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.data.offline.PendingOp
import de.dave.synchaven.data.offline.enqueue
import de.dave.synchaven.data.offline.remap
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineDataTest {
    private fun input(title: String, rrule: String? = null, exdates: List<String> = emptyList()) = EventInput(
        calendarId = "c1", title = title, description = "", location = "",
        start = "2026-11-23T09:00:00Z", end = "2026-11-23T10:00:00Z", allDay = false, timezone = "UTC",
        rrule = rrule, exdates = exdates,
    )

    private fun contactInput(name: String, photo: String? = null) =
        Contact(addressBookId = "b1", fullName = name).toInput().copy(photo = photo)

    // ---------- Queue ----------

    @Test
    fun editingOfflineCreatedEventChangesTheCreate() {
        val ops = emptyList<PendingOp>()
            .enqueue(PendingOp.CreateEvent("local-1", input("A")))
            .enqueue(PendingOp.UpdateEvent("local-1", input("B")))
        assertEquals(listOf(PendingOp.CreateEvent("local-1", input("B"))), ops)
    }

    @Test
    fun deletingOfflineCreatedEventRemovesEverything() {
        val ops = emptyList<PendingOp>()
            .enqueue(PendingOp.CreateEvent("local-1", input("A", rrule = "FREQ=DAILY")))
            .enqueue(PendingOp.AddExdate("local-1", "2026-11-24T09:00:00Z"))
            .enqueue(PendingOp.DeleteEvent("local-1"))
        assertTrue(ops.isEmpty())
    }

    @Test
    fun newerUpdateReplacesOlderAndDeleteDropsUpdates() {
        val ops = emptyList<PendingOp>()
            .enqueue(PendingOp.UpdateEvent("e1", input("A")))
            .enqueue(PendingOp.UpdateCalendar("c1", Calendar("c1", "Privat").toInput()))
            .enqueue(PendingOp.UpdateEvent("e1", input("B")))
        assertEquals(listOf(PendingOp.UpdateCalendar("c1", Calendar("c1", "Privat").toInput()), PendingOp.UpdateEvent("e1", input("B"))), ops)
        assertEquals(
            listOf(PendingOp.UpdateCalendar("c1", Calendar("c1", "Privat").toInput()), PendingOp.DeleteEvent("e1")),
            ops.enqueue(PendingOp.DeleteEvent("e1")),
        )
    }

    @Test
    fun exdateIsMergedIntoPendingUpdate() {
        val ops = emptyList<PendingOp>()
            .enqueue(PendingOp.UpdateEvent("e1", input("A", rrule = "FREQ=DAILY")))
            .enqueue(PendingOp.AddExdate("e1", "2026-11-24T09:00:00Z"))
        assertEquals(listOf(PendingOp.UpdateEvent("e1", input("A", "FREQ=DAILY", listOf("2026-11-24T09:00:00Z")))), ops)
    }

    @Test
    fun opBeingSentIsNotModified() {
        // The creation is currently being transferred: the edit comes after it as a separate change
        val sending = PendingOp.CreateEvent("local-1", input("A"))
        val ops = listOf<PendingOp>(sending).enqueue(PendingOp.UpdateEvent("local-1", input("B")), frozen = 1)
        assertEquals(listOf(sending, PendingOp.UpdateEvent("local-1", input("B"))), ops)
        assertEquals(PendingOp.UpdateEvent("e9", input("B")), ops.drop(1).remap("local-1", "e9").single())
    }

    @Test
    fun contactPhotoIsKeptWhenLaterUpdateLeavesItUnchanged() {
        val ops = emptyList<PendingOp>()
            .enqueue(PendingOp.UpdateContact("k1", contactInput("A", photo = "data:image/jpeg;base64,AA")))
            .enqueue(PendingOp.UpdateContact("k1", contactInput("B", photo = null)))
        val op = ops.single() as PendingOp.UpdateContact
        assertEquals("B", op.input.fullName)
        assertEquals("data:image/jpeg;base64,AA", op.input.photo)
    }

    @Test
    fun outboxSurvivesSerialization() {
        val data = OfflineData(outbox = listOf(
            PendingOp.CreateEvent("local-1", input("A")),
            PendingOp.DeleteContact("k1"),
            PendingOp.SetDefaultView("week"),
        ))
        val json = AppJson.encodeToString(OfflineData.serializer(), data)
        assertEquals(data, AppJson.decodeFromString(OfflineData.serializer(), json))
    }

    // ---------- Local state ----------

    private val base = OfflineData(
        calendars = listOf(Calendar("c1", "Privat"), Calendar("bd", "Geburtstage", kind = CalendarKind.BIRTHDAYS)),
        events = listOf(
            CalEvent(id = "e1", calendarId = "c1", title = "Sport", start = "2026-11-23T17:00:00Z",
                end = "2026-11-23T18:00:00Z", rrule = "FREQ=WEEKLY"),
            CalEvent(id = "b1", calendarId = "bd", title = "🎂 Anna", start = "1990-11-25T00:00:00Z",
                end = "1990-11-26T00:00:00Z", allDay = true, rrule = "FREQ=YEARLY", contactId = "k1", readOnly = true),
        ),
        addressBooks = listOf(AddressBook("b1", "Privat", contactCount = 1)),
        contacts = listOf(Contact(id = "k1", addressBookId = "b1", fullName = "Anna", birthday = "1990-11-25")),
    )

    private val week = Instant.parse("2026-11-23T00:00:00Z") to Instant.parse("2026-11-30T00:00:00Z")

    @Test
    fun occurrencesLikeTheServer() {
        val occ = base.occurrences(week.first, week.second)!!
        assertEquals(
            listOf("e1_${Instant.parse("2026-11-23T17:00:00Z").epochSecond}", "b1_${Instant.parse("2026-11-25T00:00:00Z").epochSecond}"),
            occ.map { it.id },
        )
        assertEquals("🎂 Anna (36)", occ[1].title)
        assertTrue(occ[1].readOnly && occ[1].recurring)
        assertNull(OfflineData().occurrences(week.first, week.second))
    }

    @Test
    fun pendingChangesAreAppliedAndSurviveReload() {
        val data = base.copy(outbox = listOf(
            PendingOp.AddExdate("e1", "2026-11-23T17:00:00Z"),
            PendingOp.CreateEvent("local-1", input("Neu")),
            PendingOp.CreateContact("local-2", contactInput("Bert")),
        )).withPending()
        val titles = data.occurrences(week.first, week.second)!!.map { it.title }
        assertEquals(listOf("Neu", "🎂 Anna (36)"), titles)
        assertEquals(2, data.addressBooks!!.single().contactCount)

        // Reloaded from the server: pending changes stay applied without being counted twice
        val reloaded = data.copy(events = base.events, contacts = base.contacts).withPending().withPending()
        assertEquals(data.events, reloaded.events)
        assertEquals(2, reloaded.contacts!!.size)
    }

    @Test
    fun offlineSearchMatchesTitleDescriptionAndLocation() {
        val occ = base.occurrences(week.first, week.second, query = "spo")!!
        assertEquals(listOf("Sport"), occ.map { it.title })
    }
}
