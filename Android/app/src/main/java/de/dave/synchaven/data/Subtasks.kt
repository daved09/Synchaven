package de.dave.synchaven.data

import de.dave.synchaven.data.model.Task

/*
 * Regeln für Unteraufgaben wie in backend/src/routes/tasks.rs – für den Offline-Cache, sofortige Anzeige und den
 * lokalen Modus. Unteraufgaben gibt es eine Ebene tief und nur innerhalb einer Liste.
 */

/** Unteraufgaben einer Aufgabe in der Reihenfolge, in der sie angelegt wurden */
fun List<Task>.subtasksOf(id: String): List<Task> =
    filter { it.parentId == id }.sortedWith(compareBy<Task>({ it.createdAt }, { it.id }))

/**
 * Hakt eine Aufgabe ab oder öffnet sie wieder, mit den Folgen wie bei Google Tasks: Eine erledigte Hauptaufgabe hakt
 * ihre offenen Unteraufgaben mit ab, eine wieder geöffnete Unteraufgabe öffnet auch ihre Hauptaufgabe.
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
 * Übernimmt eine gespeicherte Aufgabe [saved]: Wechselt eine Hauptaufgabe die Liste, ziehen ihre Unteraufgaben mit;
 * liegt die Hauptaufgabe einer Unteraufgabe in einer anderen Liste (oder gibt es sie nicht), wird sie zur
 * Hauptaufgabe. Ändert sich der Status, gelten die Regeln von [withTaskCompleted].
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

/** Entfernt eine Aufgabe samt ihren Unteraufgaben */
fun List<Task>.withoutTask(id: String): List<Task> = filterNot { it.id == id || it.parentId == id }

/** Entfernt die erledigten Aufgaben einer Liste samt ihren Unteraufgaben */
fun List<Task>.withoutCompleted(listId: String): List<Task> {
    val gone = filter { it.listId == listId && it.completed }.map { it.id }.toSet()
    return filterNot { it.id in gone || it.parentId in gone }
}
