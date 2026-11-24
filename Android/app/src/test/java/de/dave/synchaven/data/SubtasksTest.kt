package de.dave.synchaven.data

import de.dave.synchaven.data.api.AppJson
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.data.offline.PendingOp
import de.dave.synchaven.data.offline.remap
import de.dave.synchaven.data.offline.withCompleted
import de.dave.synchaven.util.openSubtasks
import de.dave.synchaven.util.openTasks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unteraufgaben: dieselben Regeln wie im Backend, im Cache, in der Warteschlange und in der Anzeige */
class SubtasksTest {
    private val parent = Task("p", "l1", "Umzug", createdAt = "2026-11-20T10:00:00Z")
    private val sub1 = Task("s1", "l1", "Kartons", createdAt = "2026-11-20T10:01:00Z", parentId = "p")
    private val sub2 = Task("s2", "l1", "Nachsendeauftrag", createdAt = "2026-11-20T10:02:00Z", parentId = "p")
    private val other = Task("o", "l1", "Steuer", createdAt = "2026-11-20T09:00:00Z")
    private val all = listOf(parent, sub2, sub1, other)
    private val set: (Task, Boolean) -> Task = { t, done -> t.withCompleted(done, "2026-11-24T12:00:00Z") }

    @Test
    fun parentIdIsOnlySentWhenSet() {
        val plain = AppJson.encodeToString(TaskInput.serializer(), TaskInput("l1", "A"))
        // Fehlt das Feld, bleibt die Zuordnung auf dem Server erhalten – `null` würde sie aufheben
        assertFalse(plain, plain.contains("parent_id"))
        val sub = AppJson.encodeToString(TaskInput.serializer(), TaskInput("l1", "A", parentId = "p"))
        assertTrue(sub, sub.contains("\"parent_id\":\"p\""))
        assertEquals("p", sub1.toInput().parentId)
        val decoded = AppJson.decodeFromString(Task.serializer(), """{ "id": "s", "list_id": "l1", "title": "x", "parent_id": "p" }""")
        assertEquals("p", decoded.parentId)
    }

    @Test
    fun subtasksInCreationOrder() {
        assertEquals(listOf("s1", "s2"), all.subtasksOf("p").map { it.id })
        assertEquals(listOf("o", "p"), openTasks(all, "l1").map { it.id })
        assertEquals(listOf("s1", "s2"), openSubtasks(all, "l1")["p"]?.map { it.id })
    }

    @Test
    fun openSubtaskOfCompletedParentStaysVisible() {
        val list = listOf(parent.copy(completed = true), sub1)
        assertEquals(listOf("s1"), openTasks(list, "l1").map { it.id })
    }

    @Test
    fun completingParentCompletesSubtasks() {
        val done = all.withTaskCompleted("p", true, "", set).associateBy { it.id }
        assertTrue(done.getValue("p").completed)
        assertTrue(done.getValue("s1").completed && done.getValue("s2").completed)
        assertFalse(done.getValue("o").completed)
    }

    @Test
    fun reopeningSubtaskReopensParent() {
        val done = all.withTaskCompleted("p", true, "", set)
        val reopened = done.withTaskCompleted("s1", false, "", set).associateBy { it.id }
        assertFalse(reopened.getValue("s1").completed)
        assertFalse(reopened.getValue("p").completed)
        // Die andere Unteraufgabe bleibt erledigt
        assertTrue(reopened.getValue("s2").completed)
    }

    @Test
    fun movingParentMovesSubtasksAndMovingSubtaskDetachesIt() {
        val moved = all.withTaskSaved(parent.copy(listId = "l2"), set).associateBy { it.id }
        assertEquals("l2", moved.getValue("s1").listId)
        assertEquals("l2", moved.getValue("s2").listId)
        assertEquals("l1", moved.getValue("o").listId)

        val detached = all.withTaskSaved(sub1.copy(listId = "l2"), set).associateBy { it.id }
        assertNull(detached.getValue("s1").parentId)
        assertEquals("l1", detached.getValue("s2").listId)
    }

    @Test
    fun deletingParentDeletesSubtasks() {
        assertEquals(listOf("o"), all.withoutTask("p").map { it.id })
        val cleared = all.withTaskCompleted("p", true, "", set).withoutCompleted("l1")
        assertEquals(listOf("o"), cleared.map { it.id })
    }

    @Test
    fun offlineOpsApplySubtaskRules() {
        var d = OfflineData(tasks = all)
        d = d.apply(PendingOp.SetTaskCompleted("p", true, "2026-11-24T12:00:00Z"))
        assertTrue(d.tasks!!.all { it.id == "o" || it.completed })
        d = d.apply(PendingOp.CreateTask("local-1", TaskInput("l1", "Wagen mieten", parentId = "p"), "2026-11-24T12:01:00Z"))
        assertEquals("p", d.tasks!!.first { it.id == "local-1" }.parentId)
        d = d.apply(PendingOp.DeleteTask("p"))
        assertEquals(listOf("o"), d.tasks!!.map { it.id })
    }

    @Test
    fun remapReplacesLocalParentId() {
        val ops = listOf<PendingOp>(
            PendingOp.CreateTask("local-p", TaskInput("l1", "Haupt")),
            PendingOp.CreateTask("local-s", TaskInput("l1", "Unter", parentId = "local-p")),
            PendingOp.UpdateTask("local-s", TaskInput("l1", "Unter 2", parentId = "local-p")),
        ).drop(1).remap("local-p", "server-p")
        assertEquals("server-p", (ops[0] as PendingOp.CreateTask).input.parentId)
        assertEquals("server-p", (ops[1] as PendingOp.UpdateTask).input.parentId)
        assertEquals("local-s", (ops[1] as PendingOp.UpdateTask).id)
    }
}
