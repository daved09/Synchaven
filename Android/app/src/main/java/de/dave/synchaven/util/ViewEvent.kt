package de.dave.synchaven.util

import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.Occurrence
import de.dave.synchaven.data.model.Task
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/** Occurrence with locally computed times for display – or a task with a due date ([task]) */
data class ViewEvent(
    val occ: Occurrence,
    val start: LocalDateTime,
    /** exclusive end */
    val end: LocalDateTime,
    val color: String,
    /** Task instead of event: with a check-off icon, struck through when completed */
    val task: Task? = null,
) {
    val isTask get() = task != null
    val completed get() = task?.completed == true

    val id get() = occ.id
    val title get() = occ.title
    val allDay get() = occ.allDay

    /** All-day or ≥ 24 h → shown as a bar instead of in the time grid */
    val isBar: Boolean get() = allDay || Duration.between(start, end).toHours() >= 24

    /** Last (inclusive) day on which the event is visible */
    val lastDay: LocalDate
        get() = when {
            allDay -> end.toLocalDate().minusDays(1).coerceAtLeast(start.toLocalDate())
            end == start -> start.toLocalDate()
            else -> end.minusNanos(1).toLocalDate()
        }

    fun touches(day: LocalDate) = start.toLocalDate() <= day && lastDay >= day
}

fun Occurrence.toViewEvent(calendars: Map<String, Calendar>, zone: ZoneId = deviceZone()): ViewEvent {
    val s = if (allDay) allDayToLocal(start).atStartOfDay() else parseInstant(start).toLocal(zone)
    val e = if (allDay) allDayToLocal(end).atStartOfDay() else parseInstant(end).toLocal(zone)
    return ViewEvent(this, s, e, calendars[calendarId]?.color ?: "#6750A4")
}
