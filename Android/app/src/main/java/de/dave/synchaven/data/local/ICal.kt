package de.dave.synchaven.data.local

import de.dave.synchaven.data.model.CalEvent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Minimal iCalendar (RFC 5545) export and import for VEVENTs in local mode – like `backend/src/ical.rs`,
 * so files from the app and from the server behave the same.
 */
object ICal {
    private val UTC_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
    private val LOCAL_FMT = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
    private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

    // ---------- Export ----------

    /** [reminders] returns an event's reminders (its own or the calendar default) for VALARM entries. */
    fun export(
        calendarName: String,
        events: List<CalEvent>,
        now: Instant = Instant.now(),
        reminders: (CalEvent) -> List<Int> = { it.reminders.orEmpty() },
    ): String {
        val out = StringBuilder()
        listOf("BEGIN:VCALENDAR", "VERSION:2.0", "PRODID:-//Synchaven//Synchaven Android//DE", "CALSCALE:GREGORIAN", "METHOD:PUBLISH")
            .forEach { fold(it, out) }
        fold("X-WR-CALNAME:${escape(calendarName)}", out)
        val stamp = UTC_FMT.format(now)
        for (ev in events) {
            val start = parseInstant(ev.start) ?: continue
            val end = parseInstant(ev.end) ?: start
            fold("BEGIN:VEVENT", out)
            fold("UID:${escape(ev.uid.ifEmpty { ev.id })}", out)
            fold("DTSTAMP:$stamp", out)
            fold(dtProp("DTSTART", start, ev), out)
            fold(dtProp("DTEND", end, ev), out)
            fold("SUMMARY:${escape(ev.title)}", out)
            if (ev.description.isNotEmpty()) fold("DESCRIPTION:${escape(ev.description)}", out)
            if (ev.location.isNotEmpty()) fold("LOCATION:${escape(ev.location)}", out)
            val rule = ev.rrule?.takeIf { it.isNotBlank() }
            if (rule != null) {
                fold("RRULE:${exportRrule(rule, ev.allDay)}", out)
                ev.exdates.mapNotNull(::parseInstant).forEach { fold(dtProp("EXDATE", it, ev), out) }
            }
            for (m in reminders(ev)) {
                fold("BEGIN:VALARM", out)
                fold("ACTION:DISPLAY", out)
                fold("DESCRIPTION:${escape(ev.title)}", out)
                fold("TRIGGER:${trigger(m)}", out)
                fold("END:VALARM", out)
            }
            fold("END:VEVENT", out)
        }
        fold("END:VCALENDAR", out)
        return out.toString()
    }

    /** TRIGGER of a reminder (minutes before the start; negative = after the start) */
    private fun trigger(minutes: Int): String = when {
        minutes == 0 -> "PT0S"
        minutes > 0 && minutes % 1440 == 0 -> "-P${minutes / 1440}D"
        minutes > 0 -> "-PT${minutes}M"
        else -> "PT${-minutes}M"
    }

    private fun dtProp(name: String, t: Instant, ev: CalEvent): String {
        if (ev.allDay) return "$name;VALUE=DATE:${DATE_FMT.format(t.atOffset(ZoneOffset.UTC))}"
        val zone = runCatching { ZoneId.of(ev.timezone) }.getOrNull()
        return if (zone != null && zone.normalized() != ZoneOffset.UTC) {
            "$name;TZID=${zone.id}:${LOCAL_FMT.format(t.atZone(zone))}"
        } else {
            "$name:${UTC_FMT.format(t)}"
        }
    }

    /** For all-day events, UNTIL must be a plain date. */
    private fun exportRrule(rule: String, allDay: Boolean): String = if (!allDay) rule else
        rule.split(';').joinToString(";") { p ->
            val (k, v) = p.substringBefore('=') to p.substringAfter('=', "")
            if (k == "UNTIL" && v.length >= 8) "UNTIL=${v.take(8)}" else p
        }

    fun escape(s: String): String = s.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,")
        .replace("\r\n", "\\n").replace("\n", "\\n").replace("\r", "")

    /** Fold lines after 75 octets (RFC 5545, section 3.1) without splitting UTF-8 characters. */
    fun fold(line: String, out: StringBuilder) {
        var len = 0
        var i = 0
        while (i < line.length) {
            val cp = line.codePointAt(i)
            val chars = Character.charCount(cp)
            val w = String(Character.toChars(cp)).toByteArray(Charsets.UTF_8).size
            if (len + w > 75) {
                out.append("\r\n ")
                len = 1
            }
            out.appendCodePoint(cp)
            len += w
            i += chars
        }
        out.append("\r\n")
    }

    // ---------- Import ----------

    data class ParsedEvent(
        val uid: String?,
        val title: String,
        val description: String,
        val location: String,
        val start: Instant,
        val end: Instant,
        val allDay: Boolean,
        val timezone: String,
        val rrule: String?,
        val exdates: List<Instant>,
        /** Set for modified single occurrences of a series */
        val recurrenceId: Instant?,
    )

    class Prop(val name: String, val params: List<Pair<String, String>>, val value: String) {
        fun param(key: String) = params.firstOrNull { it.first == key }?.second
    }

    fun unescape(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\') {
                if (i + 1 < s.length) {
                    val n = s[i + 1]
                    out.append(if (n == 'n' || n == 'N') '\n' else n)
                }
                i += 2
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun unfold(text: String): List<String> {
        val lines = mutableListOf<String>()
        for (raw in text.split('\n')) {
            val line = raw.removeSuffix("\r")
            if ((line.startsWith(' ') || line.startsWith('\t')) && lines.isNotEmpty()) {
                lines[lines.lastIndex] = lines.last() + line.substring(1)
            } else if (line.isNotEmpty()) {
                lines += line
            }
        }
        return lines
    }

    /** Splits at [sep], ignoring separators inside quotes. */
    fun splitUnquoted(s: String, sep: Char): List<String> {
        val parts = mutableListOf<String>()
        var inQuotes = false
        var last = 0
        s.forEachIndexed { i, c ->
            if (c == '"') inQuotes = !inQuotes
            else if (c == sep && !inQuotes) {
                parts += s.substring(last, i)
                last = i + 1
            }
        }
        parts += s.substring(last)
        return parts
    }

    /** Index of the first colon outside of quotes */
    fun valueColon(line: String): Int {
        var inQuotes = false
        line.forEachIndexed { i, c ->
            if (c == '"') inQuotes = !inQuotes
            else if (c == ':' && !inQuotes) return i
        }
        return -1
    }

    private fun parseProp(line: String): Prop? {
        val colon = valueColon(line).takeIf { it >= 0 } ?: return null
        val segs = splitUnquoted(line.substring(0, colon), ';')
        val name = segs.first().trim().uppercase()
        val params = segs.drop(1).filter { '=' in it }.map {
            it.substringBefore('=').trim().uppercase() to it.substringAfter('=').trim().trim('"')
        }
        return Prop(name, params, line.substring(colon + 1))
    }

    private val WINDOWS_ZONES = mapOf(
        "W. Europe Standard Time" to "Europe/Berlin",
        "Central Europe Standard Time" to "Europe/Budapest",
        "Central European Standard Time" to "Europe/Warsaw",
        "Romance Standard Time" to "Europe/Paris",
        "GMT Standard Time" to "Europe/London",
        "E. Europe Standard Time" to "Europe/Bucharest",
        "FLE Standard Time" to "Europe/Helsinki",
        "Eastern Standard Time" to "America/New_York",
        "Central Standard Time" to "America/Chicago",
        "Mountain Standard Time" to "America/Denver",
        "Pacific Standard Time" to "America/Los_Angeles",
        "UTC" to "UTC", "Coordinated Universal Time" to "UTC", "GMT" to "UTC",
    )

    fun zoneOrNull(id: String): ZoneId? =
        runCatching { ZoneId.of(id.trim()) }.getOrNull()?.takeIf { it.id.contains('/') || it.id == "UTC" }

    /** Map a TZID to a time zone – also Mozilla paths and common Windows names (Outlook/Exchange). */
    private fun resolveTzid(tzid: String): ZoneId? {
        val id = tzid.trim().trim('"')
        zoneOrNull(id)?.let { return it }
        // e.g. "/mozilla.org/20050126_1/Europe/Berlin"
        val segs = id.split('/').filter { it.isNotEmpty() }
        for (n in listOf(3, 2)) {
            if (segs.size >= n) zoneOrNull(segs.takeLast(n).joinToString("/"))?.let { return it }
        }
        return WINDOWS_ZONES[id]?.let { ZoneId.of(it) }
    }

    private data class DtValue(val instant: Instant, val allDay: Boolean, val zone: ZoneId)

    private fun parseDtValue(raw: String, prop: Prop, defaultZone: ZoneId): DtValue? {
        val value = raw.trim()
        val isDate = prop.param("VALUE")?.equals("DATE", ignoreCase = true) == true || value.length == 8
        return runCatching {
            when {
                isDate -> DtValue(
                    LocalDate.parse(value.take(8), DATE_FMT).atStartOfDay(ZoneOffset.UTC).toInstant(), true, defaultZone,
                )
                value.endsWith("Z") -> DtValue(
                    LocalDateTime.parse(value.dropLast(1), LOCAL_FMT).toInstant(ZoneOffset.UTC), false, ZoneOffset.UTC,
                )
                else -> {
                    val zone = prop.param("TZID")?.let(::resolveTzid) ?: defaultZone
                    DtValue(LocalDateTime.parse(value, LOCAL_FMT).atZone(zone).toInstant(), false, zone)
                }
            }
        }.getOrNull()
    }

    /** ISO 8601 duration such as `P1D`, `PT1H30M`, `P2W` */
    fun parseDuration(raw: String): Duration? {
        val t = raw.trim().trimStart('+', '-')
        if (!t.startsWith('P')) return null
        val s = t.substring(1)
        val date = s.substringBefore('T')
        val time = s.substringAfter('T', "")
        var total = Duration.ZERO
        val num = StringBuilder()
        for (c in date) {
            if (c.isDigit()) { num.append(c); continue }
            val n = num.toString().toLongOrNull() ?: return null
            num.clear()
            total += when (c) {
                'W' -> Duration.ofDays(7 * n)
                'D' -> Duration.ofDays(n)
                else -> return null
            }
        }
        for (c in time) {
            if (c.isDigit()) { num.append(c); continue }
            val n = num.toString().toLongOrNull() ?: return null
            num.clear()
            total += when (c) {
                'H' -> Duration.ofHours(n)
                'M' -> Duration.ofMinutes(n)
                'S' -> Duration.ofSeconds(n)
                else -> return null
            }
        }
        return total
    }

    private class Builder {
        var uid: String? = null
        var title = ""
        var description = ""
        var location = ""
        var start: DtValue? = null
        var end: DtValue? = null
        var duration: Duration? = null
        var rrule: String? = null
        val exdates = mutableListOf<Instant>()
        var recurrenceId: Instant? = null
        var cancelled = false

        fun finish(): ParsedEvent? {
            if (cancelled) return null
            val (from, allDay, zone) = start ?: return null
            val e = end?.instant
            val d = duration
            var endAt = when {
                e != null && e >= from -> e
                d != null -> from + d
                allDay -> from + Duration.ofDays(1)
                else -> from
            }
            if (allDay && endAt <= from) endAt = from + Duration.ofDays(1)
            return ParsedEvent(
                uid = uid, title = title.ifBlank { "(Ohne Titel)" }, description = description, location = location,
                start = from, end = endAt, allDay = allDay, timezone = if (zone.normalized() == ZoneOffset.UTC) "UTC" else zone.id,
                rrule = rrule, exdates = exdates.toList(), recurrenceId = recurrenceId,
            )
        }
    }

    /** Reads all VEVENTs; [IllegalArgumentException] if it is not an iCalendar file. */
    fun parse(text: String, defaultZone: ZoneId): List<ParsedEvent> {
        val lines = unfold(text.removePrefix("\uFEFF"))
        require(lines.any { it.trim().equals("BEGIN:VCALENDAR", ignoreCase = true) }) {
            "Keine gültige iCalendar-Datei (BEGIN:VCALENDAR fehlt)"
        }
        val events = mutableListOf<ParsedEvent>()
        var current: Builder? = null
        var nested = 0 // e.g. VALARM inside a VEVENT
        for (line in lines) {
            val prop = parseProp(line) ?: continue
            val upper = prop.value.trim().uppercase()
            val b = current
            when {
                b == null -> if (prop.name == "BEGIN" && upper == "VEVENT") {
                    current = Builder()
                    nested = 0
                }
                prop.name == "BEGIN" -> nested++
                prop.name == "END" && nested > 0 -> nested--
                prop.name == "END" && upper == "VEVENT" -> {
                    b.finish()?.let { events += it }
                    current = null
                }
                nested == 0 -> when (prop.name) {
                    "UID" -> b.uid = prop.value.trim()
                    "SUMMARY" -> b.title = unescape(prop.value)
                    "DESCRIPTION" -> b.description = unescape(prop.value)
                    "LOCATION" -> b.location = unescape(prop.value)
                    "DTSTART" -> b.start = parseDtValue(prop.value, prop, defaultZone)
                    "DTEND" -> b.end = parseDtValue(prop.value, prop, defaultZone)
                    "DURATION" -> b.duration = parseDuration(prop.value)
                    "RRULE" -> b.rrule = prop.value.trim()
                    "EXDATE" -> prop.value.split(',').forEach { v ->
                        parseDtValue(v, prop, defaultZone)?.let { b.exdates += it.instant }
                    }
                    "RECURRENCE-ID" -> b.recurrenceId = parseDtValue(prop.value, prop, defaultZone)?.instant
                    "STATUS" -> b.cancelled = upper == "CANCELLED"
                }
            }
        }
        return events
    }

    private fun parseInstant(s: String): Instant? =
        runCatching { java.time.OffsetDateTime.parse(s).toInstant() }.getOrNull()
}
