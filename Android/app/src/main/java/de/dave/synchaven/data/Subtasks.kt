package de.dave.synchaven.data

import de.dave.synchaven.data.model.Task

/*
 * Rules for subtasks as in backend/src/routes/tasks.rs – for the offline cache, immediate display and
 * local mode. Subtasks are one level deep and only within one list.
 */

/** Own order (as in the backend): by position, ties by creation time */
val TaskOrder: Comparator<Task> = compareBy<Task>({ it.position }, { it.createdAt }, { it.id })

/** Subtasks of a task in their own order */
fun List<Task>.subtasksOf(id: String): List<Task> = filter { it.parentId == id }.sortedWith(TaskOrder)

/**
 * Position for a task that newly joins the tasks with this list and parent task: new top-level tasks come first
 * (as in Google Tasks), new subtasks last – they are usually entered one after another.
 */
fun List<Task>.newPosition(listId: String, parentId: String?): Long {
    val positions = filter { it.listId == listId && it.parentId == parentId }.map { it.position }
    return if (parentId == null) positions.minOrNull()?.minus(1) ?: 0 else positions.maxOrNull()?.plus(1) ?: 0
}

/**
 * Moves a task behind [after] among the tasks with the same list and parent task (`null`, or a task that is no
 * longer among them: to the front) and numbers that level anew from 0 – like `PUT /tasks/{id}/position`.
 */
fun List<Task>.withTaskMoved(id: String, after: String?): List<Task> {
    val task = firstOrNull { it.id == id } ?: return this
    val order = filter { it.listId == task.listId && it.parentId == task.parentId && it.id != id }.sortedWith(TaskOrder)
        .toMutableList()
    order.add(order.indexOfFirst { it.id == after } + 1, task)
    val positions = order.withIndex().associate { (i, t) -> t.id to i.toLong() }
    return map { t -> positions[t.id]?.let { if (it == t.position) t else t.copy(position = it) } ?: t }
}

/**
 * Checks off a task or reopens it, with the consequences as in Google Tasks: completing a parent task also checks off
 * its open subtasks, reopening a subtask also reopens its parent task.
 */
fun List<Task>.withTaskCompleted(id: String, done: Boolean, at: String, set: (Task, Boolean) -> Task): List<Task> {
    val task = firstOrNull { it.id == id } ?: return this
    return map {
        when {
            it.id == id -> set(it, done)
            done && it.parentId == id && !it.completed -> set(it, true)
            !done && it.id == task.parentId && it.completed -> set(it, false)
            else -> it
        }
    }
}

/**
 * Takes over a saved task [saved]: if a parent task changes its list, its subtasks move along;
 * if a subtask's parent task is in another list (or doesn't exist), the subtask becomes a
 * top-level task. If the status changes, the rules of [withTaskCompleted] apply.
 * [place]: saved on the device, not by the server – a new task, or one in another list or under another parent task,
 * then gets its [newPosition].
 */
fun List<Task>.withTaskSaved(saved: Task, place: Boolean = false, set: (Task, Boolean) -> Task): List<Task> {
    val before = firstOrNull { it.id == saved.id }
    val parent = saved.parentId?.let { p -> firstOrNull { it.id == p } }
    var t = if (saved.parentId != null && (parent == null || parent.listId != saved.listId)) saved.copy(parentId = null) else saved
    if (place && (before == null || before.listId != t.listId || before.parentId != t.parentId)) {
        t = t.copy(position = filterNot { it.id == t.id }.newPosition(t.listId, t.parentId))
    }
    var list = if (before != null) map { if (it.id == t.id) t else it } else this + t
    if (before != null && before.listId != t.listId) {
        list = list.map { if (it.parentId == t.id) it.copy(listId = t.listId) else it }
    }
    if (before != null && before.completed != t.completed) {
        list = list.withTaskCompleted(t.id, t.completed, t.completedAt.orEmpty()) { x, done -> if (x.id == t.id) x else set(x, done) }
    }
    return list
}

/** Removes a task including its subtasks */
fun List<Task>.withoutTask(id: String): List<Task> = filterNot { it.id == id || it.parentId == id }

/** Removes the completed tasks of a list including their subtasks */
fun List<Task>.withoutCompleted(listId: String): List<Task> {
    val gone = filter { it.listId == listId && it.completed }.map { it.id }.toSet()
    return filterNot { it.id in gone || it.parentId in gone }
}
