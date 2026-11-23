package de.dave.synchaven.sync

import de.dave.synchaven.data.model.CalEvent
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// Umrechnung zwischen Terminen der REST API und den Spalten von CalendarContract.Events.
// Ohne Android-Abhängigkeiten, damit sie in Unit-Tests geprüft werden kann.

private const val DAY_MS = 86_400_000L

private val UTC_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
private val LOCAL_DATE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
private val BASIC_DATE = DateTimeFormatter.BASIC_ISO_DATE

/** Prüfsumme der synchronisierten Felder eines Termins – ändert sie sich, wird der lokale Termin neu geschrieben. */
fun CalEvent.syncHash(): String = sha1(
    listOf(
        calendarId, title, description, location, start, end, allDay.toString(), timezone, rrule.orEmpty(),
        exdates.joinToString(","),
    ).joinToString("\u0001")
)

fun sha1(text: String): String =
    MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }

/** Spaltenwerte eines Termins für den Android-Kalender. */
data class AndroidEventTimes(
    val dtStart: Long,
    /** nur bei Einzelterminen */
    val dtEnd: Long?,
    /** nur bei Serien (RFC 2445, z. B. `P3600S` oder `P1D`) */
    val duration: String?,
    val timezone: String,
    val rrule: String?,
    /** EXDATE-Spalte, z. B. `20261207T080000Z,20261214T080000Z` */
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
        // Ganztägige Termine führt Android in UTC
        timezone = if (allDay) "UTC" else timezone.ifBlank { "UTC" },
        rrule = rrule?.takeIf { recurring },
        exdate = if (recurring) formatExdates(exdates.map { Instant.parse(it) }) else null,
    )
}

fun formatExdates(dates: List<Instant>): String? =
    dates.takeIf { it.isNotEmpty() }?.joinToString(",") { UTC_DATE_TIME.format(it) }

/**
 * Liest die EXDATE-Spalte: `[TZID;]wert,wert…`, auch mehrere Zeilen. Werte in UTC (`…Z`), lokaler Zeit
 * (`TZID` bzw. Zeitzone des Termins) oder als Datum (dann zur Uhrzeit des Serienbeginns).
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

/** Dauer nach RFC 2445 (`PT1H30M`, `P1D`, `P2W`, auch Androids `P3600S` ohne `T`) in Millisekunden, `null` wenn ungültig. */
fun parseDurationMillis(value: String?): Long? {
    val m = DURATION.matchEntire(value?.trim()?.uppercase() ?: return null) ?: return null
    val (sign, w, d, h, min, s) = m.destructured
    fun n(x: String) = x.toLongOrNull() ?: 0L
    val seconds = ((n(w) * 7 + n(d)) * 24 + n(h)) * 3600 + n(min) * 60 + n(s)
    return seconds * 1000 * (if (sign == "-") -1 else 1)
}

/** Ende eines lokalen Termins aus DTEND oder DTSTART + DURATION. */
fun androidEventEnd(dtStart: Long, dtEnd: Long?, duration: String?, allDay: Boolean): Long =
    dtEnd?.takeIf { it >= dtStart }
        ?: parseDurationMillis(duration)?.takeIf { it >= 0 }?.let { dtStart + it }
        ?: (if (allDay) dtStart + DAY_MS else dtStart)

/** Zeitpunkt für die API: ganztägige Termine immer als Mitternacht UTC des Datums. */
fun apiTime(millis: Long, allDay: Boolean): String {
    val instant = Instant.ofEpochMilli(millis)
    return if (allDay) instant.atOffset(ZoneOffset.UTC).toLocalDate().atStartOfDay().toInstant(ZoneOffset.UTC).toString()
    else instant.toString()
}

/** Farbe `#RRGGBB` als ARGB-Wert für CALENDAR_COLOR */
fun parseColor(hex: String, fallback: Int = 0xFF6750A4.toInt()): Int {
    val v = hex.trim().removePrefix("#")
    if (v.length != 6) return fallback
    return v.toIntOrNull(16)?.let { it or 0xFF000000.toInt() } ?: fallback
}
