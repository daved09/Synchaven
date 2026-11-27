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
import de.dave.synchaven.util.openSubtasks
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
    /** Selected list (the first one as long as nothing is selected) */
    val list: TaskList? = null,
    val open: List<Task> = emptyList(),
    /** Open subtasks per parent task in [open] */
    val subtasks: Map<String, List<Task>> = emptyMap(),
    val completed: List<Task> = emptyList(),
    /** All tasks – for the details of a task that is currently open */
    val all: List<Task> = emptyList(),
    val showCompleted: Boolean = false,
    /** Never loaded (e.g. offline on first start) */
    val notLoaded: Boolean = false,
    val loading: Boolean = false,
    val error: UiText? = null,
)

/** Task panel: a list with quick entry, open and completed tasks (like the task panel on the web). */
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
            open = list?.let { openTasks(all, it.id, it.sortBy) }.orEmpty(),
            subtasks = list?.let { openSubtasks(all, it.id) }.orEmpty(),
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

    /** Quick entry: task without a date in the selected list */
    fun quickAdd(title: String) {
        val listId = state.value.list?.id ?: return
        val t = title.trim().takeIf { it.isNotEmpty() } ?: return
        launchAction { repo.createTask(TaskInput(listId, t)) }
    }

    /** New subtask without a date under [parent] */
    fun addSubtask(parent: Task, title: String) {
        val t = title.trim().takeIf { it.isNotEmpty() } ?: return
        launchAction { repo.createSubtask(parent, t) }
    }

    /** Create a new task list and select it right away */
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

    fun setSortBy(sortBy: String) {
        val list = state.value.list ?: return
        if (list.sortBy != sortBy) launchAction { repo.setSortBy(list, sortBy) }
    }

    /** Moves [task] behind [after] among its siblings (`null` = first) */
    fun move(task: Task, after: String?) = launchAction { repo.moveTask(task, after) }

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
