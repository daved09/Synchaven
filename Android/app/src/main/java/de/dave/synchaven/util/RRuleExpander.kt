package de.dave.synchaven.util

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * Lokale Expansion von RFC-5545-Wiederholungsregeln für die Offline-Ansicht – dieselbe Semantik wie
 * `backend/src/recurrence.rs`: Vorkommen behalten die Ortszeit in der Zeitzone des Termins (ganztägige in UTC),
 * EXDATEs werden exakt verglichen, geliefert werden alle Vorkommen, die den Zeitraum [from, to) berühren.
 *
 * Unterstützt FREQ (DAILY bis YEARLY), INTERVAL, COUNT, UNTIL, BYDAY (mit Ordnungszahlen), BYMONTHDAY, BYMONTH,
 * BYSETPOS und WKST – alles, was der Editor, die Weboberfläche und der Geburtstagskalender erzeugen. Bei anderen
 * Regeln wird wie beim Server für ungültige Regeln nur der erste Termin geliefert.
 */
object RRuleExpander {
    private const val MAX_OCCURRENCES = 3000
    private const val MAX_PERIODS = 200_000

    private val CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")
    private val SUPPORTED = setOf("FREQ", "INTERVAL", "COUNT", "UNTIL", "BYDAY", "BYMONTHDAY", "BYMONTH", "BYSETPOS", "WKST")
    private val BYDAY_RE = Regex("""^([+-]?\d{1,2})?([A-Z]{2})$""")
    private val UNTIL_RE = Regex("""^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})(Z?))?$""")

    private enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    private data class WeekdayNum(val n: Int?, val day: DayOfWeek)

    private data class Rule(
        val freq: Freq,
        val interval: Int,
        val count: Int?,
        val until: Instant?,
        val byDay: List<WeekdayNum>,
        val byMonthDay: List<Int>,
        val byMonth: Set<Int>,
        val bySetPos: List<Int>,
        val wkst: DayOfWeek,
    )

    /**
     * Startzeitpunkte aller Vorkommen, die [from, to) berühren.
     * @param zone Zeitzone des Termins; bei ganztägigen Terminen ignoriert (UTC)
     */
    fun occurrences(
        start: Instant,
        end: Instant,
        zone: ZoneId,
        allDay: Boolean,
        rrule: String?,
        exdates: Set<Instant>,
        from: Instant,
        to: Instant,
    ): List<Instant> {
        val duration = Duration.between(start, end)
        fun overlaps(s: Instant) = s < to && (s.plus(duration) > from || (duration.isZero && s >= from))

        if (rrule.isNullOrBlank()) return if (overlaps(start)) listOf(start) else emptyList()
        val tz = if (allDay) ZoneOffset.UTC else zone
        val rule = parse(rrule, tz) ?: return if (overlaps(start)) listOf(start) else emptyList()

        val first = start.atZone(tz)
        val date0 = first.toLocalDate()
        val time0 = first.toLocalTime()
        val lastDate = to.atZone(tz).toLocalDate().plusDays(1)

        val out = ArrayList<Instant>()
        var produced = 0
        var period = 0L
        loop@ while (period < MAX_PERIODS) {
            val periodStart = periodStart(rule, date0, period)
            if (periodStart > lastDate) break
            for (d in candidates(rule, date0, period)) {
                if (d < date0) continue
                val inst = ZonedDateTime.of(d, time0, tz).toInstant()
                if (inst < start) continue
                if (rule.until != null && inst > rule.until) break@loop
                if (rule.count != null && produced >= rule.count) break@loop
                produced++
                if (inst >= to) break@loop
                if (overlaps(inst) && inst !in exdates) {
                    out += inst
                    if (out.size >= MAX_OCCURRENCES) break@loop
                }
            }
            period++
        }
        return out
    }

    private fun parse(raw: String, zone: ZoneId): Rule? {
        val parts = raw.trim().replace(Regex("^RRULE:", RegexOption.IGNORE_CASE), "").uppercase()
            .split(';').map { it.trim() }.filter { it.isNotEmpty() }
            .map { p -> p.substringBefore('=') to p.substringAfter('=', "") }
        if (parts.isEmpty() || parts.any { it.first !in SUPPORTED }) return null
        val p = parts.toMap()
        val freq = Freq.entries.firstOrNull { it.name == p["FREQ"] } ?: return null
        val interval = p["INTERVAL"]?.let { it.toIntOrNull() ?: return null } ?: 1
        if (interval < 1) return null
        val count = p["COUNT"]?.let { it.toIntOrNull() ?: return null }
        val until = p["UNTIL"]?.let { parseUntil(it, zone) ?: return null }
        val byDay = p["BYDAY"]?.split(',')?.map { code ->
            val m = BYDAY_RE.matchEntire(code.trim()) ?: return null
            val day = CODES.indexOf(m.groupValues[2]).takeIf { it >= 0 } ?: return null
            WeekdayNum(m.groupValues[1].takeIf { it.isNotEmpty() }?.removePrefix("+")?.toInt(), DayOfWeek.of(day + 1))
        }.orEmpty()
        val byMonthDay = p["BYMONTHDAY"]?.split(',')?.map { it.trim().toIntOrNull() ?: return null }.orEmpty()
        val byMonth = p["BYMONTH"]?.split(',')?.map { it.trim().toIntOrNull()?.takeIf { m -> m in 1..12 } ?: return null }
            .orEmpty().toSet()
        val bySetPos = p["BYSETPOS"]?.split(',')?.map { it.trim().toIntOrNull() ?: return null }.orEmpty()
        val wkst = p["WKST"]?.let { code -> CODES.indexOf(code).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) } ?: return null }
            ?: DayOfWeek.MONDAY
        return Rule(freq, interval, count, until, byDay, byMonthDay, byMonth, bySetPos, wkst)
    }

    /** UNTIL mit Z ist UTC, sonst Ortszeit des Termins; ein reines Datum schließt den ganzen Tag ein (wie der Server). */
    private fun parseUntil(v: String, zone: ZoneId): Instant? {
        val m = UNTIL_RE.matchEntire(v.trim()) ?: return null
        val (y, mo, d) = m.destructured
        val date = runCatching { LocalDate.of(y.toInt(), mo.toInt(), d.toInt()) }.getOrNull() ?: return null
        if (m.groupValues[4].isEmpty()) return date.atTime(23, 59, 59).atZone(zone).toInstant()
        val time = runCatching {
            LocalTime.of(m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt())
        }.getOrNull() ?: return null
        val local = LocalDateTime.of(date, time)
        return if (m.groupValues[7] == "Z") local.toInstant(ZoneOffset.UTC) else local.atZone(zone).toInstant()
    }

    private fun periodStart(rule: Rule, date0: LocalDate, period: Long): LocalDate {
        val step = period * rule.interval
        return when (rule.freq) {
            Freq.DAILY -> date0.plusDays(step)
            Freq.WEEKLY -> date0.with(TemporalAdjusters.previousOrSame(rule.wkst)).plusWeeks(step)
            Freq.MONTHLY -> date0.withDayOfMonth(1).plusMonths(step)
            Freq.YEARLY -> date0.withDayOfYear(1).plusYears(step)
        }
    }

    /** Sortierte Kandidaten eines Zeitraums (Tag, Woche, Monat oder Jahr) nach BYxxx und BYSETPOS. */
    private fun candidates(rule: Rule, date0: LocalDate, period: Long): List<LocalDate> {
        val start = periodStart(rule, date0, period)
        val dates: List<LocalDate> = when (rule.freq) {
            Freq.DAILY -> listOf(start).filter { d ->
                (rule.byMonth.isEmpty() || d.monthValue in rule.byMonth) &&
                    (rule.byMonthDay.isEmpty() || monthDays(YearMonth.from(d), rule.byMonthDay).contains(d)) &&
                    (rule.byDay.isEmpty() || rule.byDay.any { it.day == d.dayOfWeek })
            }
            Freq.WEEKLY -> {
                val days = rule.byDay.map { it.day }.ifEmpty { listOf(date0.dayOfWeek) }.distinct()
                days.map { start.plusDays(((it.value - rule.wkst.value + 7) % 7).toLong()) }.filter { d ->
                    (rule.byMonth.isEmpty() || d.monthValue in rule.byMonth) &&
                        (rule.byMonthDay.isEmpty() || monthDays(YearMonth.from(d), rule.byMonthDay).contains(d))
                }
            }
            Freq.MONTHLY -> {
                val ym = YearMonth.from(start)
                if (rule.byMonth.isNotEmpty() && ym.monthValue !in rule.byMonth) emptyList()
                else monthCandidates(rule, ym, date0)
            }
            Freq.YEARLY -> yearCandidates(rule, start.year, date0)
        }
        val sorted = dates.distinct().sorted()
        if (rule.bySetPos.isEmpty() || sorted.isEmpty()) return sorted
        return rule.bySetPos.mapNotNull { pos ->
            when {
                pos > 0 -> sorted.getOrNull(pos - 1)
                pos < 0 -> sorted.getOrNull(sorted.size + pos)
                else -> null
            }
        }.distinct().sorted()
    }

    private fun monthCandidates(rule: Rule, ym: YearMonth, date0: LocalDate): List<LocalDate> {
        val md = if (rule.byMonthDay.isEmpty()) null else monthDays(ym, rule.byMonthDay)
        val bd = if (rule.byDay.isEmpty()) null else weekdaysIn(ym.atDay(1), ym.atEndOfMonth(), rule.byDay)
        return when {
            md != null && bd != null -> md.filter { it in bd }
            md != null -> md
            bd != null -> bd
            date0.dayOfMonth <= ym.lengthOfMonth() -> listOf(ym.atDay(date0.dayOfMonth))
            else -> emptyList()
        }
    }

    private fun yearCandidates(rule: Rule, year: Int, date0: LocalDate): List<LocalDate> {
        if (rule.byMonth.isNotEmpty()) {
            return rule.byMonth.flatMap { month ->
                val ym = YearMonth.of(year, month)
                if (rule.byMonthDay.isNotEmpty() || rule.byDay.isNotEmpty()) monthCandidates(rule, ym, date0)
                else if (date0.dayOfMonth <= ym.lengthOfMonth()) listOf(ym.atDay(date0.dayOfMonth))
                else emptyList()
            }
        }
        if (rule.byMonthDay.isNotEmpty()) {
            return (1..12).flatMap { monthDays(YearMonth.of(year, it), rule.byMonthDay) }
                .filter { d -> rule.byDay.isEmpty() || rule.byDay.any { it.day == d.dayOfWeek } }
        }
        if (rule.byDay.isNotEmpty()) {
            return weekdaysIn(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), rule.byDay)
        }
        val ym = YearMonth.of(year, date0.month)
        return if (date0.dayOfMonth <= ym.lengthOfMonth()) listOf(ym.atDay(date0.dayOfMonth)) else emptyList()
    }

    /** BYMONTHDAY eines Monats; negative Werte zählen vom Monatsende, ungültige Tage entfallen. */
    private fun monthDays(ym: YearMonth, days: List<Int>): List<LocalDate> {
        val len = ym.lengthOfMonth()
        return days.mapNotNull { d ->
            val day = if (d < 0) len + d + 1 else d
            if (day in 1..len) ym.atDay(day) else null
        }
    }

    /** BYDAY innerhalb [first, last]: ohne Zahl alle passenden Wochentage, mit Zahl der n-te (negativ: vom Ende). */
    private fun weekdaysIn(first: LocalDate, last: LocalDate, byDay: List<WeekdayNum>): List<LocalDate> =
        byDay.flatMap { wd ->
            val firstMatch = first.with(TemporalAdjusters.nextOrSame(wd.day))
            val all = generateSequence(firstMatch) { it.plusWeeks(1) }.takeWhile { it <= last }.toList()
            when {
                wd.n == null || wd.n == 0 -> all
                wd.n > 0 -> listOfNotNull(all.getOrNull(wd.n - 1))
                else -> listOfNotNull(all.getOrNull(all.size + wd.n))
            }
        }
}
