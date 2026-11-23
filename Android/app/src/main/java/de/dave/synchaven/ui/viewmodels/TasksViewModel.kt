package de.dave.synchaven.ui.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.TaskRepository
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskInput
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.data.model.TaskListInput
import de.dave.synchaven.data.userMessage
import de.dave.synchaven.pluralText
import de.dave.synchaven.ui.common.UiMessages
import de.dave.synchaven.uiText
import de.dave.synchaven.util.completedTasks
import de.dave.synchaven.util.openTasks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TasksUiState(
    val lists: List<TaskList> = emptyList(),
    /** Gewählte Liste (erste, solange nichts gewählt ist) */
    val list: TaskList? = null,
    val open: List<Task> = emptyList(),
    val completed: List<Task> = emptyList(),
    /** Alle Aufgaben – für die Details einer gerade geöffneten Aufgabe */
    val all: List<Task> = emptyList(),
    val showCompleted: Boolean = false,
    /** Noch nie geladen (z. B. offline beim ersten Start) */
    val notLoaded: Boolean = false,
    val loading: Boolean = false,
    val error: UiText? = null,
)

/** Aufgabenbereich: eine Liste mit Schnelleingabe, offenen und erledigten Aufgaben (wie das Aufgaben-Panel im Web). */
class TasksViewModel(private val repo: TaskRepository) : ViewModel() {
    val messages = UiMessages()

    private val selectedId = MutableStateFlow<String?>(null)
    private val showCompleted = MutableStateFlow(false)
    private val loading = MutableStateFlow(false)
    private val error = MutableStateFlow<UiText?>(null)

    val state: StateFlow<TasksUiState> = combine(
        combine(repo.lists, repo.tasks, ::Pair), selectedId, showCompleted, loading, error,
    ) { (lists, tasks), sel, showDone, isLoading, err ->
        val all = tasks.orEmpty()
        val l = lists.orEmpty()
        val list = l.firstOrNull { it.id == sel } ?: l.firstOrNull()
        TasksUiState(
            lists = l, list = list,
            open = list?.let { openTasks(all, it.id) }.orEmpty(),
            completed = list?.let { completedTasks(all, it.id) }.orEmpty(),
            all = all, showCompleted = showDone,
            notLoaded = lists == null, loading = isLoading, error = err,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TasksUiState())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            loading.value = true
            runCatching { repo.load() }
                .onSuccess { error.value = null }
                .onFailure { if (repo.lists.value == null) error.value = it.userMessage() else messages.send(it.userMessage()) }
            loading.value = false
        }
    }

    fun selectList(id: String) {
        selectedId.value = id
    }

    fun toggleShowCompleted() = showCompleted.update { !it }

    /** Schnelleingabe: Aufgabe ohne Datum in der gewählten Liste */
    fun quickAdd(title: String) {
        val listId = state.value.list?.id ?: return
        val t = title.trim().takeIf { it.isNotEmpty() } ?: return
        launchAction { repo.createTask(TaskInput(listId, t)) }
    }

    /** Neue Aufgabenliste anlegen und gleich auswählen */
    fun createList(input: TaskListInput) = launchAction {
        val list = repo.createList(input)
        selectedId.value = list.id
        messages.send(uiText(R.string.msg_task_list_created))
    }

    fun save(id: String?, input: TaskInput) = launchAction {
        if (id == null) repo.createTask(input) else repo.updateTask(id, input)
        messages.send(uiText(if (id == null) R.string.msg_task_created else R.string.msg_task_saved))
    }

    fun setCompleted(task: Task, completed: Boolean) = launchAction {
        repo.setCompleted(task, completed)
        messages.send(uiText(if (completed) R.string.msg_task_done else R.string.msg_task_reopened))
    }

    fun delete(task: Task) = launchAction {
        repo.deleteTask(task.id)
        messages.send(uiText(R.string.msg_task_deleted))
    }

    fun clearCompleted() {
        val listId = state.value.list?.id ?: return
        launchAction {
            val n = repo.clearCompleted(listId)
            messages.send(pluralText(R.plurals.msg_tasks_cleared, n))
        }
    }

    private fun launchAction(block: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { block() }.onFailure { messages.send(it.userMessage()) }
        }
    }
}
