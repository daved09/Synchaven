package de.dave.synchaven.util

import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.TaskOrder
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.model.TASK_SORT_DUE
import de.dave.synchaven.data.model.TASK_SORT_MANUAL
import de.dave.synchaven.data.model.Task
import de.dave.synchaven.data.model.TaskList
import de.dave.synchaven.toUiText
import de.dave.synchaven.uiText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** Prefix of the IDs under which tasks appear in the calendar (separate from event IDs) */
const val TASK_PREFIX = "task:"

/** Height of a timed task in the time grid */
const val TASK_MINUTES = 30L

private const val DEFAULT_TASK_COLOR = "#1A73E8"

/** Due date in local time (all-day: midnight of the day); `null` = without date */
fun Task.dueLocal(zone: ZoneId = deviceZone()): LocalDateTime? {
    val d = due ?: return null
    return runCatching { if (allDay) allDayToLocal(d).atStartOfDay() else parseInstant(d).toLocal(zone) }.getOrNull()
}

/** Due date for the API: all-day as midnight UTC of the day, otherwise the point in time in UTC; without date `null`. */
fun taskDue(date: LocalDate?, time: LocalTime?, zone: ZoneId = deviceZone()): String? = when {
    date == null -> null
    time == null -> localToAllDay(date)
    else -> date.atTime(time).toInstant(zone).toString()
}

/** Task as a calendar entry: all-day for one day, with a time [TASK_MINUTES] minutes. */
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

/** Tasks with a due date from visible lists – for the calendar views */
fun calendarTasks(lists: List<TaskList>, tasks: List<Task>, zone: ZoneId = deviceZone()): List<ViewEvent> {
    val visible = lists.filter { it.visible }.associateBy { it.id }
    return tasks.mapNotNull { t -> visible[t.listId]?.let { t.toViewEvent(it.color.ifEmpty { DEFAULT_TASK_COLOR }, zone) } }
}

/**
 * Open top-level tasks of a list in the list's order: own order ([TASK_SORT_MANUAL]) or by due date, without date
 * last. Open subtasks without an open parent task in the list are listed here too, so nothing gets lost.
 */
fun openTasks(tasks: List<Task>, listId: String, sortBy: String = TASK_SORT_DUE): List<Task> {
    val open = tasks.filter { it.listId == listId && !it.completed }
    val openIds = open.mapTo(HashSet()) { it.id }
    val byDue = compareBy<Task>({ it.due == null }, { it.due?.let(::parseInstant) }, { it.createdAt }, { it.id })
    return open.filter { it.parentId == null || it.parentId !in openIds }
        .sortedWith(if (sortBy == TASK_SORT_MANUAL) TaskOrder else byDue)
}

/** Open subtasks per open top-level task of the list, in their own order */
fun openSubtasks(tasks: List<Task>, listId: String): Map<String, List<Task>> =
    tasks.filter { it.listId == listId && !it.completed && it.parentId != null }
        .sortedWith(TaskOrder)
        .groupBy { it.parentId!! }

/** Completed tasks of a list, most recently completed first */
fun completedTasks(tasks: List<Task>, listId: String): List<Task> =
    tasks.filter { it.listId == listId && it.completed }.sortedByDescending { it.completedAt.orEmpty() }

/** Overdue: open and due date passed (all-day: before today) */
fun Task.isOverdue(now: LocalDateTime = LocalDateTime.now(), zone: ZoneId = deviceZone()): Boolean {
    if (completed) return false
    val d = dueLocal(zone) ?: return false
    return if (allDay) d.toLocalDate() < now.toLocalDate() else d < now
}

/** Short due date for lists: "Today", "Tomorrow", "Yesterday" or "Tue, Nov 24", with the time if set */
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
