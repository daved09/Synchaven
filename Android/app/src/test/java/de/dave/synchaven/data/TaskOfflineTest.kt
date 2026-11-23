package de.dave.synchaven.data

import de.dave.synchaven.data.api.AppJson
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.model.TaskListInput
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.data.offline.PendingOp
import de.dave.synchaven.data.offline.enqueue
import de.dave.synchaven.data.offline.remap
import de.dave.synchaven.data.offline.toLocalTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Aufgaben im Offline-Cache: Mapping der API, lokale Anwendung von Änderungen und die Warteschlange */
class TaskOfflineTest {
    private fun input(title: String, due: String? = null, allDay: Boolean = true, completed: Boolean? = null) =
        TaskInput(listId = "l1", title = title, due = due, allDay = allDay, completed = completed)

    // ---------- Mapping ----------

    @Test
    fun decodesTaskAndListFromApi() {
        val t = AppJson.decodeFromString(Task.serializer(), """
            { "id": "t1", "list_id": "l1", "title": "Steuer abgeben", "notes": "", "due": "2026-11-25T00:00:00Z",
              "all_day": true, "completed": false, "completed_at": null, "created_at": "2026-11-20T10:00:00Z",
              "updated_at": "2026-11-20T10:00:00Z" }
        """)
        assertEquals(Task("t1", "l1", "Steuer abgeben", due = "2026-11-25T00:00:00Z", allDay = true,
            createdAt = "2026-11-20T10:00:00Z", updatedAt = "2026-11-20T10:00:00Z"), t)
        val l = AppJson.decodeFromString(TaskList.serializer(), """
            { "id": "l1", "name": "Meine Aufgaben", "color": "#1A73E8", "visible": true, "open_count": 3,
              "created_at": "…", "updated_at": "…" }
        """)
        assertEquals(3, l.openCount)
        assertEquals(TaskListInput("Meine Aufgaben", "#1A73E8", true), l.toInput())
    }

    @Test
    fun inputEncodesLikeTheWebClient() {
        val json = AppJson.encodeToString(TaskInput.serializer(), input("Zahnarzt", "2026-11-24T08:30:00Z", allDay = false))
        assertTrue(json, json.contains("\"list_id\":\"l1\""))
        assertTrue(json, json.contains("\"all_day\":false"))
        assertTrue(json, json.contains("\"due\":\"2026-11-24T08:30:00Z\""))
        // Ohne `completed` bleibt der Status auf dem Server erhalten
        assertTrue(json, json.contains("\"completed\":null"))
        // Bearbeiten aus einer vorhandenen Aufgabe lässt den Status ebenfalls unverändert
        assertNull(Task("t1", "l1", "A", completed = true).toInput().completed)
    }

    @Test
    fun localTaskMatchesServerNormalization() {
        // Ganztägig: Mitternacht UTC des Tages
        val allDay = input("A", "2026-11-24T15:30:00Z").toLocalTask("x")
        assertEquals("2026-11-24T00:00:00Z", allDay.due)
        // Mit Uhrzeit bleibt der Zeitpunkt
        assertEquals("2026-11-24T15:30:00Z", input("A", "2026-11-24T15:30:00Z", allDay = false).toLocalTask("x").due)
        // Ohne Datum immer ganztägig; Titel wird getrimmt
        val noDate = input("  Einkaufen ", allDay = false).toLocalTask("x")
        assertTrue(noDate.allDay)
        assertNull(noDate.due)
        assertEquals("Einkaufen", noDate.title)
    }

    @Test
    fun editKeepsCompletionStatusAndTime() {
        val done = Task("t1", "l1", "A", completed = true, completedAt = "2026-11-20T10:00:00Z", createdAt = "c")
        val edited = input("B").toLocalTask("t1", base = done)
        assertTrue(edited.completed)
        assertEquals("2026-11-20T10:00:00Z", edited.completedAt)
        assertEquals("c", edited.createdAt)
        val reopened = input("B", completed = false).toLocalTask("t1", base = done)
        assertFalse(reopened.completed)
        assertNull(reopened.completedAt)
    }

    // ---------- Lokale Anwendung ----------

    private val base = OfflineData(
        taskLists = listOf(TaskList("l1", "Meine Aufgaben", openCount = 9), TaskList("l2", "Einkauf", visible = false)),
        tasks = listOf(
            Task("t1", "l1", "Steuer", due = "2026-11-25T00:00:00Z"),
            Task("t2", "l1", "Alt", completed = true, completedAt = "2026-11-01T08:00:00Z"),
            Task("t3", "l2", "Milch"),
        ),
    )

    @Test
    fun pendingTaskChangesAreAppliedAndRecounted() {
        val data = base.copy(outbox = listOf(
            PendingOp.CreateTask("local-1", input("Neu"), createdAt = "2026-11-23T09:00:00Z"),
            PendingOp.SetTaskCompleted("t1", true, at = "2026-11-23T10:00:00Z"),
            PendingOp.SetTaskCompleted("t2", false),
            PendingOp.DeleteTask("t3"),
            PendingOp.UpdateTaskList("l2", TaskListInput("Einkauf", visible = true)),
        )).withPending()
        val byId = data.tasks!!.associateBy { it.id }
        assertEquals(setOf("t1", "t2", "local-1"), byId.keys)
        assertTrue(byId.getValue("t1").completed)
        assertEquals("2026-11-23T10:00:00Z", byId.getValue("t1").completedAt)
        assertFalse(byId.getValue("t2").completed)
        assertNull(byId.getValue("t2").completedAt)
        assertEquals("2026-11-23T09:00:00Z", byId.getValue("local-1").createdAt)
        // Offene Aufgaben je Liste aus den Einträgen: local-1 und t2 offen, t1 erledigt
        assertEquals(listOf(2, 0), data.taskLists!!.map { it.openCount })
        assertTrue(data.taskLists[1].visible)
        assertEquals("#1A73E8", data.taskLists[1].color)

        // Neu vom Server geladen: die ausstehenden Änderungen bleiben eingerechnet, ohne doppelt zu zählen
        val reloaded = data.copy(tasks = base.tasks, taskLists = base.taskLists).withPending().withPending()
        assertEquals(data.tasks, reloaded.tasks)
        assertEquals(data.taskLists, reloaded.taskLists)
    }

    @Test
    fun completionTimeIsKeptWhileCompleted() {
        val data = base.copy(outbox = listOf(PendingOp.SetTaskCompleted("t2", true, at = "2026-11-23T10:00:00Z"))).withPending()
        assertEquals("2026-11-01T08:00:00Z", data.tasks!!.first { it.id == "t2" }.completedAt)
    }

    @Test
    fun withoutTaskDataNothingIsInvented() {
        // Lokaler Modus / älterer Server: Aufgaben bleiben `null`
        val data = OfflineData().withPending().recount()
        assertNull(data.tasks)
        assertNull(data.taskLists)
    }

    // ---------- Warteschlange ----------

    @Test
    fun editingAndCompletingOfflineCreatedTaskChangesTheCreate() {
        val ops = emptyList<PendingOp>()
            .enqueue(PendingOp.CreateTask("local-1", input("A"), "ts"))
            .enqueue(PendingOp.SetTaskCompleted("local-1", true, "ts2"))
            .enqueue(PendingOp.UpdateTask("local-1", input("B")))
        assertEquals(listOf(PendingOp.CreateTask("local-1", input("B", completed = true), "ts")), ops)
    }

    @Test
    fun deletingOfflineCreatedTaskRemovesEverything() {
        val ops = emptyList<PendingOp>()
            .enqueue(PendingOp.CreateTask("local-1", input("A")))
            .enqueue(PendingOp.SetTaskCompleted("local-1", true))
            .enqueue(PendingOp.DeleteTask("local-1"))
        assertTrue(ops.isEmpty())
    }

    @Test
    fun newerChangesReplaceOlderOnesOfTheSameTask() {
        val ops = emptyList<PendingOp>()
            .enqueue(PendingOp.SetTaskCompleted("t1", true, "a"))
            .enqueue(PendingOp.UpdateTask("t1", input("A")))
            .enqueue(PendingOp.UpdateTaskList("l1", TaskListInput("X", visible = false)))
            .enqueue(PendingOp.SetTaskCompleted("t1", false, "b"))
            .enqueue(PendingOp.UpdateTask("t1", input("B")))
            .enqueue(PendingOp.UpdateTaskList("l1", TaskListInput("X", visible = true)))
        assertEquals(
            listOf(
                PendingOp.SetTaskCompleted("t1", false, "b"),
                PendingOp.UpdateTask("t1", input("B")),
                PendingOp.UpdateTaskList("l1", TaskListInput("X", visible = true)),
            ),
            ops,
        )
        // Löschen verwirft alle Änderungen an der Aufgabe, nicht aber die der Liste
        assertEquals(
            listOf(PendingOp.UpdateTaskList("l1", TaskListInput("X", visible = true)), PendingOp.DeleteTask("t1")),
            ops.enqueue(PendingOp.DeleteTask("t1")),
        )
    }

    @Test
    fun taskAndEventWithSameIdAreIndependent() {
        val ops = emptyList<PendingOp>()
            .enqueue(PendingOp.DeleteEvent("x1"))
            .enqueue(PendingOp.DeleteTask("x1"))
        assertEquals(listOf(PendingOp.DeleteEvent("x1"), PendingOp.DeleteTask("x1")), ops)
    }

    @Test
    fun remapReplacesLocalTaskIds() {
        val ops = listOf(
            PendingOp.UpdateTask("local-1", input("A")),
            PendingOp.SetTaskCompleted("local-1", true),
            PendingOp.DeleteTask("local-1"),
        ).remap("local-1", "t9")
        assertEquals(listOf("t9", "t9", "t9"), ops.map { it.targetId })
    }

    @Test
    fun taskDataAndOutboxSurviveSerialization() {
        val data = base.copy(outbox = listOf(
            PendingOp.CreateTask("local-1", input("A", "2026-11-24T08:30:00Z", allDay = false), "ts"),
            PendingOp.UpdateTask("t1", input("B")),
            PendingOp.SetTaskCompleted("t1", true, "ts"),
            PendingOp.DeleteTask("t3"),
            PendingOp.UpdateTaskList("l1", TaskListInput("X", "#0B57D0", false)),
        ))
        val json = AppJson.encodeToString(OfflineData.serializer(), data)
        assertEquals(data, AppJson.decodeFromString(OfflineData.serializer(), json))
        // Ältere Caches ohne Aufgaben bleiben lesbar
        assertNull(AppJson.decodeFromString(OfflineData.serializer(), """{"outbox":[]}""").tasks)
    }
}
