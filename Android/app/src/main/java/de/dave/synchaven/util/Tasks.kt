package de.dave.synchaven.util

import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.toUiText
import de.dave.synchaven.uiText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Präfix der IDs, unter denen Aufgaben im Kalender erscheinen (getrennt von Termin-IDs) */
const val TASK_PREFIX = "task:"

/** Höhe einer Aufgabe mit Uhrzeit im Zeitraster */
const val TASK_MINUTES = 30L

private const val DEFAULT_TASK_COLOR = "#1A73E8"

/** Fälligkeit in lokaler Zeit (ganztägig: Mitternacht des Tages); `null` = ohne Datum */
fun Task.dueLocal(zone: ZoneId = deviceZone()): LocalDateTime? {
    val d = due ?: return null
    return runCatching { if (allDay) allDayToLocal(d).atStartOfDay() else parseInstant(d).toLocal(zone) }.getOrNull()
}

/** Fälligkeit für die API: ganztägig als Mitternacht UTC des Tages, sonst der Zeitpunkt in UTC; ohne Datum `null`. */
fun taskDue(date: LocalDate?, time: LocalTime?, zone: ZoneId = deviceZone()): String? = when {
    date == null -> null
    time == null -> localToAllDay(date)
    else -> date.atTime(time).toInstant(zone).toString()
}

/** Aufgabe als Kalendereintrag: ganztägig einen Tag lang, mit Uhrzeit [TASK_MINUTES] Minuten. */
fun Task.toViewEvent(color: String, zone: ZoneId = deviceZone()): ViewEvent? {
    val start = dueLocal(zone) ?: return null
    val end = if (allDay) start.plusDays(1) else start.plusMinutes(TASK_MINUTES)
    val occ = Occurrence(
        id = TASK_PREFIX + id,
        eventId = id,
        calendarId = listId,
        title = title,
        description = notes,
        start = due!!,
        end = if (allDay) localToAllDay(end.toLocalDate()) else end.toInstant(zone).toString(),
        allDay = allDay,
    )
    return ViewEvent(occ, start, end, color, task = this)
}

/** Aufgaben mit Fälligkeit aus eingeblendeten Listen – für die Kalenderansichten */
fun calendarTasks(lists: List<TaskList>, tasks: List<Task>, zone: ZoneId = deviceZone()): List<ViewEvent> {
    val visible = lists.filter { it.visible }.associateBy { it.id }
    return tasks.mapNotNull { t -> visible[t.listId]?.let { t.toViewEvent(it.color.ifEmpty { DEFAULT_TASK_COLOR }, zone) } }
}

/**
 * Offene Hauptaufgaben einer Liste nach Fälligkeit, ohne Datum zuletzt. Offene Unteraufgaben ohne offene Hauptaufgabe
 * in der Liste stehen ebenfalls hier, damit nichts verloren geht.
 */
fun openTasks(tasks: List<Task>, listId: String): List<Task> {
    val open = tasks.filter { it.listId == listId && !it.completed }
    val openIds = open.mapTo(HashSet()) { it.id }
    return open.filter { it.parentId == null || it.parentId !in openIds }
        .sortedWith(compareBy<Task>({ it.due == null }, { it.due?.let(::parseInstant) }, { it.createdAt }))
}

/** Offene Unteraufgaben je offener Hauptaufgabe der Liste, in der Reihenfolge, in der sie angelegt wurden */
fun openSubtasks(tasks: List<Task>, listId: String): Map<String, List<Task>> =
    tasks.filter { it.listId == listId && !it.completed && it.parentId != null }
        .sortedWith(compareBy<Task>({ it.createdAt }, { it.id }))
        .groupBy { it.parentId!! }

/** Erledigte Aufgaben einer Liste, zuletzt erledigte zuerst */
fun completedTasks(tasks: List<Task>, listId: String): List<Task> =
    tasks.filter { it.listId == listId && it.completed }.sortedByDescending { it.completedAt.orEmpty() }

/** Überfällig: offen und Fälligkeit vorbei (ganztägig: vor heute) */
fun Task.isOverdue(now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = deviceZone()): Boolean {
    if (completed) return false
    val d = dueLocal(zone) ?: return false
    return if (allDay) d.toLocalDate() < now.toLocalDate() else d < now
}

/** Kurze Fälligkeit für Listen: „Heute“, „Morgen“, „Gestern“ oder „Mo., 24. Nov.“, ggf. mit Uhrzeit */
fun Task.dueText(today: LocalDate = LocalDate.now(), zone: ZoneId = deviceZone()): UiText? {
    val d = dueLocal(zone) ?: return null
    val day = d.toLocalDate()
    val dayText = when (day) {
        today -> uiText(R.string.task_due_today)
        today.plusDays(1) -> uiText(R.string.task_due_tomorrow)
        today.minusDays(1) -> uiText(R.string.task_due_yesterday)
        else -> day.format(if (day.year == today.year) FMT_TASK_DAY else FMT_TASK_DAY_YEAR).toUiText()
    }
    return if (allDay) dayText else uiText(R.string.task_due_with_time, dayText, d.format(FMT_TIME))
}
