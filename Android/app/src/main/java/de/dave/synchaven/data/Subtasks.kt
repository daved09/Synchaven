package de.dave.synchaven.data

import de.dave.synchaven.data.model.Task

/*
 * Rules for subtasks as in backend/src/routes/tasks.rs – for the offline cache, immediate display and
 * local mode. Subtasks are one level deep and only within one list.
 */

/** Subtasks of a task in the order they were created */
fun List<Task>.subtasksOf(id: String): List<Task> =
    filter { it.parentId == id }.sortedWith(compareBy<Task>({ it.createdAt }, { it.id }))

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
 */
fun List<Task>.withTaskSaved(saved: Task, set: (Task, Boolean) -> Task): List<Task> {
    val before = firstOrNull { it.id == saved.id }
    val parent = saved.parentId?.let { p -> firstOrNull { it.id == p } }
    val t = if (saved.parentId != null && (parent == null || parent.listId != saved.listId)) saved.copy(parentId = null) else saved
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
