package de.dave.synchaven.data

import de.dave.synchaven.data.api.AppJson
import de.dave.synchaven.data.model.TASK_SORT_DUE
import de.dave.synchaven.data.model.TASK_SORT_MANUAL
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.offline.OfflineData
import de.dave.synchaven.data.offline.PendingOp
import de.dave.synchaven.data.offline.enqueue
import de.dave.synchaven.data.offline.remap
import de.dave.synchaven.data.offline.toLocalTask
import de.dave.synchaven.data.offline.withCompleted
import de.dave.synchaven.util.openSubtasks
import de.dave.synchaven.util.openTasks
import org.junit.Assert.assertEquals
import org.junit.Test

/** Own order of tasks: the same rules as `PUT /tasks/{id}/position` in the backend, in the cache and the queue */
class TaskOrderTest {
    private fun task(id: String, position: Long, parentId: String? = null, due: String? = null) =
        Task(id, "l1", id, due = due, parentId = parentId, position = position, createdAt = "2026-11-20T10:00:00Z")

    private val a = task("a", 0)
    private val b = task("b", 1, due = "2026-11-21T00:00:00Z")
    private val c = task("c", 2, due = "2026-11-20T00:00:00Z")
    private val s1 = task("s1", 0, parentId = "a")
    private val s2 = task("s2", 1, parentId = "a")
    private val all = listOf(c, s2, a, b, s1)
    private val set: (Task, Boolean) -> Task = { t, done -> t.withCompleted(done, "") }

    private fun List<Task>.ids(parentId: String? = null) =
        filter { it.parentId == parentId }.sortedWith(TaskOrder).map { it.id }

    @Test
    fun listsAreSortedByTheirOwnOrderOrByDate() {
        assertEquals(listOf("a", "b", "c"), openTasks(all, "l1", TASK_SORT_MANUAL).map { it.id })
        assertEquals(listOf("c", "b", "a"), openTasks(all, "l1", TASK_SORT_DUE).map { it.id })
        assertEquals(listOf("s1", "s2"), openSubtasks(all, "l1")["a"]?.map { it.id })
    }

    @Test
    fun movingPlacesBehindTheGivenTaskAndRenumbers() {
        val toEnd = all.withTaskMoved("a", "c")
        assertEquals(listOf("b", "c", "a"), toEnd.ids())
        assertEquals(listOf(0L, 1L, 2L), toEnd.filter { it.parentId == null }.sortedWith(TaskOrder).map { it.position })
        assertEquals(listOf("c", "a", "b"), all.withTaskMoved("c", null).ids())
        // Subtasks only among each other; the top-level tasks stay as they are
        val subs = all.withTaskMoved("s1", "s2")
        assertEquals(listOf("s2", "s1"), subs.ids("a"))
        assertEquals(listOf("a", "b", "c"), subs.ids())
        // A neighbour that is gone (e.g. deleted meanwhile): to the front
        assertEquals(listOf("b", "a", "c"), all.withTaskMoved("b", "gone").ids())
    }

    @Test
    fun newTasksComeFirstAndNewSubtasksLast() {
        val withTask = all.withTaskSaved(TaskInput("l1", "neu").toLocalTask("n"), place = true, set = set)
        assertEquals(listOf("n", "a", "b", "c"), withTask.ids())
        val withSub = all.withTaskSaved(TaskInput("l1", "neu", parentId = "a").toLocalTask("n"), place = true, set = set)
        assertEquals(listOf("s1", "s2", "n"), withSub.ids("a"))
        // Saved by the server: its position counts
        val fromServer = all.withTaskSaved(task("n", 5), set = set)
        assertEquals(listOf("a", "b", "c", "n"), fromServer.ids())
        // Edited in place: stays where it is
        val edited = all.withTaskSaved(b.copy(title = "B"), place = true, set = set)
        assertEquals(listOf("a", "b", "c"), edited.ids())
    }

    @Test
    fun onlyTheLastMoveOfATaskIsQueued() {
        val queue = emptyList<PendingOp>()
            .enqueue(PendingOp.MoveTask("a", "b"))
            .enqueue(PendingOp.MoveTask("c", null))
            .enqueue(PendingOp.MoveTask("a", "c"))
        assertEquals(listOf(PendingOp.MoveTask("c", null), PendingOp.MoveTask("a", "c")), queue)
        // Deleting the task drops its moves
        assertEquals(listOf(PendingOp.MoveTask("c", null), PendingOp.DeleteTask("a")), queue.enqueue(PendingOp.DeleteTask("a")))
    }

    @Test
    fun movesBehindOfflineCreatedTasksAreRemapped() {
        val queue = listOf(PendingOp.MoveTask("local-1", "a"), PendingOp.MoveTask("b", "local-1"))
        assertEquals(listOf(PendingOp.MoveTask("srv", "a"), PendingOp.MoveTask("b", "srv")), queue.remap("local-1", "srv"))
    }

    @Test
    fun pendingMovesAreAppliedAndSurviveSerialization() {
        val d = OfflineData(
            taskLists = listOf(TaskList("l1", "Meine Aufgaben", sortBy = TASK_SORT_MANUAL)),
            tasks = all,
            outbox = listOf(PendingOp.MoveTask("c", null)),
        )
        assertEquals(listOf("c", "a", "b"), d.withPending().tasks!!.ids())
        val json = AppJson.encodeToString(OfflineData.serializer(), d)
        assertEquals(d.outbox, AppJson.decodeFromString(OfflineData.serializer(), json).outbox)
    }

    @Test
    fun listsWithoutSortOrderComeFromOlderServers() {
        val old = AppJson.decodeFromString(TaskList.serializer(), """{"id":"l1","name":"Alt"}""")
        assertEquals(TASK_SORT_DUE, old.sortBy)
        val task = AppJson.decodeFromString(Task.serializer(), """{"id":"t","list_id":"l1","title":"x"}""")
        assertEquals(0L, task.position)
    }
}
