package de.dave.synchaven.data

import de.dave.synchaven.data.api.ApiException
import de.dave.synchaven.data.api.SyncHavenApi
import de.dave.synchaven.data.api.apiCall
import de.dave.synchaven.data.model.CompletedRequest
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.model.TaskListInput
import de.dave.synchaven.data.offline.OfflineStore
import de.dave.synchaven.data.offline.OfflineSync
import de.dave.synchaven.data.offline.PendingOp
import de.dave.synchaven.data.offline.fetchTasks
import de.dave.synchaven.data.offline.isOfflineError
import de.dave.synchaven.data.offline.newLocalId
import de.dave.synchaven.data.offline.toLocalTask
import de.dave.synchaven.data.offline.withCompleted
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Aufgabenlisten und Aufgaben. Wie Kalender und Termine liegen sie im [OfflineStore]; Anlegen, Bearbeiten, Abhaken
 * und Löschen funktionieren auch ohne Verbindung (Warteschlange von [OfflineSync]). Listen anlegen und löschen geht
 * nur mit Server. Im lokalen Modus speichert die [de.dave.synchaven.data.local.LocalApi] sie auf dem Gerät.
 */
class TaskRepository(
    private val api: SyncHavenApi,
    private val store: OfflineStore,
    private val sync: OfflineSync,
    scope: CoroutineScope,
) {
    /** `null`: noch nie geladen oder vom Server nicht unterstützt */
    val lists: StateFlow<List<TaskList>?> = store.data.map { it.taskLists }.stateIn(scope, SharingStarted.Eagerly, null)
    val tasks: StateFlow<List<Task>?> = store.data.map { it.tasks }.stateIn(scope, SharingStarted.Eagerly, null)

    /** Lädt Listen und Aufgaben vom Server (offline bleibt es beim Cache). */
    suspend fun load() {
        sync.read(preferCache = sync.hasAnyPending, cached = { d -> d.taskLists?.let { Unit } }) {
            val (lists, tasks) = fetchTasks(api) ?: return@read
            sync.mergeServer { it.copy(taskLists = lists, tasks = tasks) }
        }
    }

    // ---------- Listen ----------

    suspend fun createList(input: TaskListInput): TaskList {
        val l = sync.online { apiCall { api.createTaskList(input) } }
        sync.mergeServer { d -> d.copy(taskLists = d.taskLists.orEmpty().filterNot { it.id == l.id } + l) }
        return l
    }

    suspend fun updateList(id: String, input: TaskListInput): TaskList = sync.write(
        PendingOp.UpdateTaskList(id, input),
        online = {
            val l = apiCall { api.updateTaskList(id, input) }
            sync.mergeServer { d -> d.copy(taskLists = d.taskLists?.map { if (it.id == id) l else it }) }
            l
        },
        offline = { d -> d.taskLists?.firstOrNull { it.id == id } ?: TaskList(id, input.name) },
    )

    /** Blendet die Aufgaben einer Liste im Kalender ein/aus – sofort lokal, danach auf dem Server. */
    suspend fun toggleVisible(list: TaskList) {
        val toggled = list.copy(visible = !list.visible)
        sync.mergeServer { it.apply(PendingOp.UpdateTaskList(list.id, toggled.toInput())) }
        try {
            updateList(list.id, toggled.toInput())
        } catch (e: Exception) {
            sync.mergeServer { it.apply(PendingOp.UpdateTaskList(list.id, list.toInput())) }
            throw e
        }
    }

    suspend fun deleteList(id: String) {
        sync.online { apiCall { api.deleteTaskList(id) } }
        sync.mergeServer { d ->
            d.copy(taskLists = d.taskLists?.filterNot { it.id == id }, tasks = d.tasks?.filterNot { it.listId == id })
        }
    }

    /**
     * Löscht alle erledigten Aufgaben einer Liste und liefert ihre Anzahl. Mit Verbindung in einem Aufruf, sonst
     * (oder wenn noch Änderungen ausstehen, die der Server noch nicht kennt) einzeln über die Warteschlange.
     */
    suspend fun clearCompleted(listId: String): Int {
        store.awaitReady()
        val done = store.data.value.tasks.orEmpty().filter { it.listId == listId && it.completed }
        if (!sync.hasAnyPending) {
            try {
                val res = sync.online { apiCall { api.clearCompletedTasks(listId) } }
                sync.mergeServer { d -> d.copy(tasks = d.tasks?.withoutCompleted(listId)) }
                return res.deleted
            } catch (e: Throwable) {
                if (!e.isOfflineError()) throw e
            }
        }
        done.forEach { deleteTask(it.id) }
        return done.size
    }

    // ---------- Aufgaben ----------

    fun task(id: String): Task? = store.data.value.let { d -> d.tasks?.firstOrNull { it.id == d.resolve(id) } }

    suspend fun createTask(task: TaskInput): Task {
        // Die Hauptaufgabe kann inzwischen übertragen worden sein und eine neue ID haben
        val input = task.parentId?.let { p -> task.copy(parentId = store.data.value.resolve(p)) } ?: task
        val localId = newLocalId()
        return sync.write(
            PendingOp.CreateTask(localId, input, now()),
            online = { apiCall { api.createTask(input) }.also { patchTask(it) } },
            offline = { d -> d.tasks?.firstOrNull { it.id == localId } ?: input.toLocalTask(localId) },
        )
    }

    /** Neue Unteraufgabe ohne Datum unter [parent] */
    suspend fun createSubtask(parent: Task, title: String): Task =
        createTask(TaskInput(parent.listId, title, parentId = parent.id))

    suspend fun updateTask(id: String, input: TaskInput): Task = sync.write(
        PendingOp.UpdateTask(id, input),
        online = { apiCall { api.updateTask(id, input) }.also { patchTask(it) } },
        offline = { d -> d.tasks?.firstOrNull { it.id == d.resolve(id) } ?: input.toLocalTask(d.resolve(id)) },
    )

    /** Abhaken oder wieder öffnen – sofort sichtbar, bei einem Fehler zurück (wie in der Weboberfläche). */
    suspend fun setCompleted(task: Task, completed: Boolean): Task {
        val at = now()
        val before = store.data.value.tasks
        sync.mergeServer { d -> d.copy(tasks = d.tasks?.withTaskCompleted(task.id, completed, at) { t, done -> t.withCompleted(done, at) }) }
        return try {
            sync.write(
                PendingOp.SetTaskCompleted(task.id, completed, at),
                online = { apiCall { api.setTaskCompleted(task.id, CompletedRequest(completed)) }.also { patchTask(it) } },
                offline = { d -> d.tasks?.firstOrNull { it.id == d.resolve(task.id) } ?: task.withCompleted(completed, at) },
            )
        } catch (e: Exception) {
            // Zurück auf den Stand davor (samt mit abgehakten Unteraufgaben bzw. wieder geöffneter Hauptaufgabe)
            val ids = before.orEmpty().associateBy { it.id }
            sync.mergeServer { d -> d.copy(tasks = d.tasks?.map { ids[it.id] ?: it }) }
            throw e
        }
    }

    suspend fun deleteTask(id: String) {
        sync.write(
            PendingOp.DeleteTask(id),
            online = {
                try {
                    apiCall { api.deleteTask(id) }
                } catch (e: ApiException) {
                    if (e.status != 404) throw e
                }
                sync.mergeServer { d -> d.copy(tasks = d.tasks?.withoutTask(id)) }
            },
            offline = { },
        )
    }

    /** Übernimmt eine vom Server gelieferte Aufgabe in den Cache. */
    private suspend fun patchTask(t: Task) {
        sync.mergeServer { d -> d.copy(tasks = d.tasks.orEmpty().withTaskSaved(t) { x, done -> x.withCompleted(done, now()) }) }
    }

    private fun now(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()
}
