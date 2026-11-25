package de.dave.synchaven.util

import android.content.res.Resources
import de.dave.synchaven.R
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.EventInput
import de.dave.synchaven.data.model.Task
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.nullable
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Event reminders – the same semantics as `backend/src/reminders.rs`: minutes before the start. For all-day
 * events the value refers to midnight in the device's time zone; negative values are a time on the
 * day itself (-540 = 09:00).
 */
object Reminders {
    const val MAX_COUNT = 5
    const val DAY = 1440
    const val MAX_BEFORE = 4 * 7 * DAY

    /** Preset for new calendars (like the server): 15 minutes before, or all-day on the day itself at 09:00 */
    val DEFAULT_TIMED = listOf(15)
    val DEFAULT_ALL_DAY = listOf(-(9 * 60))

    val TIMED_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120, DAY, 2 * DAY, 7 * DAY)
    val ALL_DAY_PRESETS = listOf(allDayMinutes(0, 9), allDayMinutes(1, 9), allDayMinutes(1, 18), allDayMinutes(2, 9), allDayMinutes(7, 9))

    /** Minutes for "[daysBefore] days before at [hour]:[minute]" for all-day events */
    fun allDayMinutes(daysBefore: Int, hour: Int, minute: Int = 0) = daysBefore * DAY - (hour * 60 + minute)

    fun isValid(minutes: Int, allDay: Boolean) = minutes <= MAX_BEFORE && minutes >= if (allDay) -(DAY - 1) else 0

    fun sorted(list: List<Int>) = list.distinct().sorted()

    /** Days before, hour, minute of a reminder for an all-day event */
    fun splitAllDay(minutes: Int): Triple<Int, Int, Int> {
        val days = maxOf(0, Math.floorDiv(minutes + DAY - 1, DAY))
        val ofDay = days * DAY - minutes
        return Triple(days, ofDay / 60, ofDay % 60)
    }

    fun describe(res: Resources, minutes: Int, allDay: Boolean): String {
        if (allDay) {
            val (days, hour, minute) = splitAllDay(minutes)
            val time = LocalTime.of(hour, minute).format(FMT_TIME)
            return when {
                days == 0 -> res.getString(R.string.reminder_all_day_same, time)
                days == 1 -> res.getString(R.string.reminder_all_day_before, time)
                days % 7 == 0 -> res.getQuantityString(R.plurals.reminder_all_day_weeks, days / 7, days / 7, time)
                else -> res.getQuantityString(R.plurals.reminder_all_day_days, days, days, time)
            }
        }
        fun q(id: Int, n: Int) = res.getQuantityString(id, n, n)
        return when {
            minutes == 0 -> res.getString(R.string.reminder_at_start)
            minutes % (7 * DAY) == 0 -> q(R.plurals.reminder_weeks, minutes / (7 * DAY))
            minutes % DAY == 0 -> q(R.plurals.reminder_days, minutes / DAY)
            minutes % 60 == 0 -> q(R.plurals.reminder_hours, minutes / 60)
            else -> q(R.plurals.reminder_minutes, minutes)
        }
    }

    /** Like the server's validation; throws [InvalidRemindersException] (local mode turns it into the server's message). */
    fun validate(list: List<Int>, allDay: Boolean): List<Int> {
        val l = sorted(list)
        if (l.size > MAX_COUNT) throw InvalidRemindersException(tooMany = true, allDay)
        if (!l.all { isValid(it, allDay) }) throw InvalidRemindersException(tooMany = false, allDay)
        return l
    }

    /** [tooMany]: more than [MAX_COUNT] reminders, otherwise one is out of range */
    class InvalidRemindersException(val tooMany: Boolean, val allDay: Boolean) :
        IllegalArgumentException(if (tooMany) "too many reminders" else "reminder out of range (allDay=$allDay)")

    /** A due reminder for an occurrence */
    data class Due(
        val event: CalEvent,
        /** Start of the occurrence (all-day: midnight UTC of the date) */
        val start: Instant,
        val end: Instant,
        val minutes: Int,
        /** Time of the reminder */
        val at: Instant,
    ) {
        val key get() = "${event.id}_${start.epochSecond}_$minutes"
    }

    /** All reminders of the event whose time lies in (from, to]; [zone] determines the start of all-day events. */
    fun due(ev: CalEvent, reminders: List<Int>, zone: ZoneId, from: Instant, to: Instant): List<Due> {
        if (reminders.isEmpty()) return emptyList()
        val start = parseInstant(ev.start) ?: return emptyList()
        val end = parseInstant(ev.end) ?: start
        val duration = Duration.between(start, end)
        val eventZone = runCatching { ZoneId.of(ev.timezone) }.getOrDefault(ZoneOffset.UTC)
        val exdates = ev.exdates.mapNotNull(::parseInstant).toSet()
        val slack = if (ev.allDay) Duration.ofDays(1) else Duration.ZERO
        return reminders.flatMap { m ->
            val before = Duration.ofMinutes(m.toLong())
            RRuleExpander.occurrences(
                start, end, eventZone, ev.allDay, ev.rrule, exdates,
                from.plus(before).minus(slack), to.plus(before).plus(slack).plusSeconds(1),
            ).mapNotNull { s ->
                val begins = if (ev.allDay) LocalDateTime.ofInstant(s, ZoneOffset.UTC).atZone(zone).toInstant() else s
                val at = begins.minus(before)
                if (at <= from || at > to) null else Due(ev, s, s.plus(duration), m, at)
            }
        }
    }

    /** Earliest reminder time in (from, to] or `null` */
    fun next(events: List<CalEvent>, calendars: List<Calendar>, zone: ZoneId, from: Instant, to: Instant): Instant? {
        val byId = calendars.associateBy { it.id }
        return events.asSequence().flatMap { due(it, it.effectiveReminders(byId[it.calendarId]), zone, from, to) }
            .minOfOrNull { it.at }
    }

    /** Reminder for all-day tasks: on the due date at 09:00 (like the server) */
    const val TASK_ALL_DAY_MINUTE = 9 * 60

    /** A due reminder for a task */
    data class TaskDue(val task: Task, val at: Instant) {
        val key get() = "task_${task.id}_${task.due}"
    }

    /**
     * Time of the reminder for an open task (like Google Tasks): at the due time, or for all-day
     * tasks on the due date at 09:00 in [zone]; `null` for completed tasks and tasks without a date.
     */
    fun taskReminderAt(task: Task, zone: ZoneId): Instant? {
        if (task.completed) return null
        val due = task.due?.let(::parseInstant) ?: return null
        return if (task.allDay) {
            LocalDateTime.ofInstant(due, ZoneOffset.UTC).plusMinutes(TASK_ALL_DAY_MINUTE.toLong()).atZone(zone).toInstant()
        } else due
    }

    /** Reminders for tasks whose time lies in (from, to] */
    fun dueTasks(tasks: List<Task>, zone: ZoneId, from: Instant, to: Instant): List<TaskDue> =
        tasks.mapNotNull { t -> taskReminderAt(t, zone)?.takeIf { it > from && it <= to }?.let { TaskDue(t, it) } }

    /** Earliest reminder time of a task in (from, to] or `null` */
    fun nextTask(tasks: List<Task>, zone: ZoneId, from: Instant, to: Instant): Instant? =
        dueTasks(tasks, zone, from, to).minOfOrNull { it.at }

    private fun parseInstant(s: String): Instant? = runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
}

/** Own reminders or the calendar's default reminders */
fun CalEvent.effectiveReminders(calendar: Calendar?): List<Int> =
    reminders ?: calendar?.defaultReminders(allDay).orEmpty()

/** Value for [EventInput.reminders]: own reminders or `null` for the calendar default */
fun remindersField(list: List<Int>?): JsonElement = list?.let { l -> JsonArray(l.map { JsonPrimitive(it) }) } ?: JsonNull

/**
 * Effect of [EventInput.reminders] on an existing event with [current]: field missing → unchanged,
 * `null` → calendar default, list → own reminders.
 */
fun EventInput.applyReminders(current: List<Int>?): List<Int>? = when (val r = reminders) {
    null -> current
    is JsonArray -> r.mapNotNull { it.jsonPrimitive.intOrNull }
    else -> null
}

/**
 * Serializes [EventInput.reminders] so that `null` is kept in the JSON as [JsonNull] (calendar default)
 * instead of being read as "field missing" – important for stored offline changes.
 */
object ReminderFieldSerializer : KSerializer<JsonElement?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor.nullable
    override fun serialize(encoder: Encoder, value: JsonElement?) =
        encoder.encodeSerializableValue(JsonElement.serializer(), value ?: JsonNull)
    override fun deserialize(decoder: Decoder): JsonElement? = (decoder as JsonDecoder).decodeJsonElement()
}
