package de.dave.synchaven.util

import android.content.res.Resources
import de.dave.synchaven.R
import de.dave.synchaven.data.model.CalEvent
import de.dave.synchaven.data.model.Calendar
import de.dave.synchaven.data.model.EventInput
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
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
 * Erinnerungen an Termine – dieselbe Semantik wie `backend/src/reminders.rs`: Minuten vor Beginn. Bei ganztägigen
 * Terminen bezieht sich der Wert auf Mitternacht in der Zeitzone des Geräts; negative Werte sind eine Uhrzeit am
 * Tag selbst (-540 = 09:00).
 */
object Reminders {
    const val MAX_COUNT = 5
    const val DAY = 1440
    const val MAX_BEFORE = 4 * 7 * DAY

    /** Voreinstellung neuer Kalender (wie der Server): 15 Minuten vorher bzw. ganztägig am Tag selbst um 09:00 */
    val DEFAULT_TIMED = listOf(15)
    val DEFAULT_ALL_DAY = listOf(-(9 * 60))

    val TIMED_PRESETS = listOf(0, 5, 10, 15, 30, 60, 120, DAY, 2 * DAY, 7 * DAY)
    val ALL_DAY_PRESETS = listOf(allDayMinutes(0, 9), allDayMinutes(1, 9), allDayMinutes(1, 18), allDayMinutes(2, 9), allDayMinutes(7, 9))

    /** Minuten für „[daysBefore] Tage vorher um [hour]:[minute]“ bei ganztägigen Terminen */
    fun allDayMinutes(daysBefore: Int, hour: Int, minute: Int = 0) = daysBefore * DAY - (hour * 60 + minute)

    fun isValid(minutes: Int, allDay: Boolean) = minutes <= MAX_BEFORE && minutes >= if (allDay) -(DAY - 1) else 0

    fun sorted(list: List<Int>) = list.distinct().sorted()

    /** Tage vorher, Stunde, Minute einer Erinnerung an einen ganztägigen Termin */
    fun splitAllDay(minutes: Int): Triple<Int, Int, Int> {
        val days = maxOf(0, Math.floorDiv(minutes + DAY - 1, DAY))
        val ofDay = days * DAY - minutes
        return Triple(days, ofDay / 60, ofDay % 60)
    }

    fun describe(res: Resources, minutes: Int, allDay: Boolean): String {
        if (allDay) {
            val (days, hour, minute) = splitAllDay(minutes)
            val time = "%02d:%02d".format(hour, minute)
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

    /** Wie die Prüfung des Servers; wirft [IllegalArgumentException] mit verständlicher Meldung. */
    fun validate(list: List<Int>, allDay: Boolean): List<Int> {
        val l = sorted(list)
        require(l.size <= MAX_COUNT) { "Höchstens $MAX_COUNT Erinnerungen pro Termin" }
        require(l.all { isValid(it, allDay) }) {
            if (allDay) "Erinnerungen an ganztägige Termine: höchstens 4 Wochen vorher, spätestens 23:59 am Tag selbst"
            else "Erinnerungen: höchstens 4 Wochen und frühestens zum Beginn des Termins"
        }
        return l
    }

    /** Eine fällige Erinnerung an ein Vorkommen */
    data class Due(
        val event: CalEvent,
        /** Beginn des Vorkommens (ganztägig: Mitternacht UTC des Datums) */
        val start: Instant,
        val end: Instant,
        val minutes: Int,
        /** Zeitpunkt der Erinnerung */
        val at: Instant,
    ) {
        val key get() = "${event.id}_${start.epochSecond}_$minutes"
    }

    /** Alle Erinnerungen des Termins, deren Zeitpunkt in (from, to] liegt; [zone] bestimmt den Beginn ganztägiger Termine. */
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

    /** Frühester Erinnerungszeitpunkt in (from, to] oder `null` */
    fun next(events: List<CalEvent>, calendars: List<Calendar>, zone: ZoneId, from: Instant, to: Instant): Instant? {
        val byId = calendars.associateBy { it.id }
        return events.asSequence().flatMap { due(it, it.effectiveReminders(byId[it.calendarId]), zone, from, to) }
            .minOfOrNull { it.at }
    }

    private fun parseInstant(s: String): Instant? = runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
}

/** Eigene Erinnerungen oder die Standard-Erinnerungen des Kalenders */
fun CalEvent.effectiveReminders(calendar: Calendar?): List<Int> =
    reminders ?: calendar?.defaultReminders(allDay).orEmpty()

/** Wert für [EventInput.reminders]: eigene Erinnerungen oder `null` für den Standard des Kalenders */
fun remindersField(list: List<Int>?): JsonElement = list?.let { l -> JsonArray(l.map { JsonPrimitive(it) }) } ?: JsonNull

/**
 * Auswirkung von [EventInput.reminders] auf einen bestehenden Termin mit [current]: Feld fehlt → unverändert,
 * `null` → Kalender-Standard, Liste → eigene Erinnerungen.
 */
fun EventInput.applyReminders(current: List<Int>?): List<Int>? = when (val r = reminders) {
    null -> current
    is JsonArray -> r.mapNotNull { it.jsonPrimitive.intOrNull }
    else -> null
}

/**
 * Serialisiert [EventInput.reminders] so, dass `null` im JSON als [JsonNull] erhalten bleibt (Kalender-Standard)
 * statt als „Feld fehlt“ gelesen zu werden – wichtig für gespeicherte Offline-Änderungen.
 */
object ReminderFieldSerializer : KSerializer<JsonElement?> {
    override val descriptor: SerialDescriptor = JsonElement.serializer().descriptor.nullable
    override fun serialize(encoder: Encoder, value: JsonElement?) =
        encoder.encodeSerializableValue(JsonElement.serializer(), value ?: JsonNull)
    override fun deserialize(decoder: Decoder): JsonElement? = (decoder as JsonDecoder).decodeJsonElement()
}
