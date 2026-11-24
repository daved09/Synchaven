package de.dave.synchaven.sync

import de.dave.synchaven.data.model.CalEvent
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Conversion between events of the REST API and the columns of CalendarContract.Events.
// Without Android dependencies, so it can be checked in unit tests.

private const val DAY_MS = 86_400_000L

private val UTC_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
private val LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
private val BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE

/** Checksum of an event's synced fields – if it changes, the local event is rewritten. */
fun CalEvent.syncHash(): String = sha1(
    listOf(
        calendarId, title, description, location, start, end, allDay.toString(), timezone, rrule.orEmpty(),
        exdates.joinToString(","),
    ).joinToString("\u0001")
)

fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** Column values of an event for the Android calendar. */
data class AndroidEventTimes(
    val dtStart: Long,
    /** only for single events */
    val dtEnd: Long?,
    /** only for series (RFC 2445, e.g. `P3600S` or `P1D`) */
    val duration: String?,
    val timezone: String,
    val rrule: String?,
    /** EXDATE column, e.g. `20261207T080000Z,20261214T080000Z` */
    val exdate: String?,
)

fun CalEvent.toAndroidTimes(): AndroidEventTimes {
    val start = Instant.parse(start).toEpochMilli()
    var end = Instant.parse(end).toEpochMilli()
    if (allDay && end <= start) end = start + DAY_MS
    val recurring = !rrule.isNullOrBlank()
    val duration = when {
        !recurring -> null
        allDay -> "P${maxOf(1, (end - start) / DAY_MS)}D"
        else -> "P${maxOf(0, (end - start) / 1000)}S"
    }
    return AndroidEventTimes(
        dtStart = start,
        dtEnd = if (recurring) null else end,
        duration = duration,
        // Android keeps all-day events in UTC
        timezone = if (allDay) "UTC" else timezone.ifBlank { "UTC" },
        rrule = rrule?.takeIf { recurring },
        exdate = if (recurring) formatExdates(exdates.map { Instant.parse(it) }) else null,
    )
}

fun formatExdates(dates: List<Instant>): String? =
    dates.takeIf { it.isNotEmpty() }?.joinToString(",") { UTC_DATE_TIME.format(it) }

/**
 * Reads the EXDATE column: `[TZID;]value,value…`, also multiple lines. Values in UTC (`…Z`), local time
 * (`TZID` or the event's time zone) or as a date (then at the time of the series start).
 */
fun parseExdates(value: String?, eventTimezone: String, dtStart: Long, allDay: Boolean): List<Instant> {
    if (value.isNullOrBlank()) return emptyList()
    val eventZone = zoneOrUtc(if (allDay) "UTC" else eventTimezone)
    val startTime = Instant.ofEpochMilli(dtStart).atZone(eventZone).toLocalTime()
    val out = mutableListOf<Instant>()
    for (rawLine in value.split('\n')) {
        var line = rawLine.trim()
        if (line.isEmpty()) continue
        var zone = eventZone
        val semicolon = line.indexOf(';')
        if (semicolon >= 0) {
            zone = zoneOrUtc(line.substring(0, semicolon).removePrefix("TZID="))
            line = line.substring(semicolon + 1)
        }
        for (raw in line.split(',')) {
            val v = raw.trim()
            val instant = runCatching {
                when {
                    v.endsWith("Z") -> Instant.from(UTC_DATE_TIME.parse(v))
                    v.contains('T') -> LocalDateTime.parse(v, LOCAL_DATE_TIME).atZone(zone).toInstant()
                    else -> LocalDate.parse(v, BASIC_DATE).atTime(startTime).atZone(eventZone).toInstant()
                }
            }.getOrNull()
            if (instant != null) out += instant
        }
    }
    return out.distinct().sorted()
}

private fun zoneOrUtc(id: String): ZoneId = runCatching { ZoneId.of(id.trim()) }.getOrDefault(ZoneOffset.UTC)

private val DURATION = Regex("""^([+-])?P(?:(\d+)W)?(?:(\d+)D)?(?:T?(?:(\d+)H)?(?:(\d+)M)?(?:(\d+)S)?)?$""")

/** Duration according to RFC 2445 (`PT1H30M`, `P1D`, `P2W`, also Android's `P3600S` without `T`) in milliseconds, `null` if invalid. */
fun parseDurationMillis(value: String?): Long? {
    val m = DURATION.matchEntire(value?.trim()?.uppercase() ?: return null) ?: return null
    val (sign, w, d, h, min, s) = m.destructured
    fun n(x: String) = x.toLongOrNull() ?: 0L
    val seconds = ((n(w) * 7 + n(d)) * 24 + n(h)) * 3600 + n(min) * 60 + n(s)
    return seconds * 1000 * (if (sign == "-") -1 else 1)
}

/** End of a local event from DTEND or DTSTART + DURATION. */
fun androidEventEnd(dtStart: Long, dtEnd: Long?, duration: String?, allDay: Boolean): Long =
    dtEnd?.takeIf { it >= dtStart }
        ?: parseDurationMillis(duration)?.takeIf { it >= 0 }?.let { dtStart + it }
        ?: (if (allDay) dtStart + DAY_MS else dtStart)

/** Point in time for the API: all-day events always as midnight UTC of the date. */
fun apiTime(millis: Long, allDay: Boolean): String {
    val instant = Instant.ofEpochMilli(millis)
    return if (allDay) instant.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toString()
    else instant.toString()
}

/** Color `#RRGGBB` as an ARGB value for CALENDAR_COLOR */
fun parseColor(hex: String, fallback: Int = 0xFF6750A4.toInt()): Int {
    val v = hex.trim().removePrefix("#")
    if (v.length != 6) return fallback
    return v.toIntOrNull(16)?.let { it or 0xFF000000.toInt() } ?: fallback
}
