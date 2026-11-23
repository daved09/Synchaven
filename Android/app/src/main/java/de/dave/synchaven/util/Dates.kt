package de.dave.synchaven.util

import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.store.ViewMode
import de.dave.synchaven.toUiText
import de.dave.synchaven.uiText
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale

// Datumshilfen – angezeigt wird immer in der Zeitzone des Geräts.
// Die Datumsmuster (Reihenfolge, Abkürzungen) folgen bewusst dem deutschen Format der Weboberfläche.

val LOCALE: Locale = Locale.GERMANY

/** Anzahl Tage der Terminliste ab dem gewählten Datum */
const val LIST_DAYS = 60L

fun deviceZone(): ZoneId = ZoneId.systemDefault()

/** RFC-3339-Zeitpunkt mit beliebigem Offset → Instant */
fun parseInstant(s: String): Instant = OffsetDateTime.parse(s).toInstant()

fun Instant.toLocal(zone: ZoneId = deviceZone()): LocalDateTime = atZone(zone).toLocalDateTime()

fun LocalDateTime.toInstant(zone: ZoneId = deviceZone()): Instant = atZone(zone).toInstant()

fun LocalDate.startInstant(zone: ZoneId = deviceZone()): Instant = atStartOfDay(zone).toInstant()

/** Ganztägige Termine werden als Mitternacht UTC übertragen; nur der Datumsteil zählt. */
fun allDayToLocal(iso: String): LocalDate = LocalDate.parse(iso.substring(0, 10))

fun localToAllDay(d: LocalDate): String = "${d}T00:00:00Z"

/** Zeitpunkt mit Offset der Gerätezeitzone, z. B. `2026-11-26T14:30:00+01:00` */
fun LocalDateTime.toOffsetString(zone: ZoneId = deviceZone()): String =
    atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

fun startOfWeek(d: LocalDate, weekStart: DayOfWeek): LocalDate =
    d.with(TemporalAdjusters.previousOrSame(weekStart))

fun isoWeek(d: LocalDate): Int = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

private fun fmt(pattern: String): DateTimeFormatter = DateTimeFormatter.ofPattern(pattern, LOCALE)

val FMT_TIME: DateTimeFormatter = fmt("HH:mm")
val FMT_DATE_SHORT: DateTimeFormatter = fmt("dd.MM.yyyy")
val FMT_DATE_LONG: DateTimeFormatter = fmt("d. MMMM yyyy")
val FMT_DAY_MONTH: DateTimeFormatter = fmt("d. MMMM")
val FMT_MONTH_YEAR: DateTimeFormatter = fmt("LLLL yyyy")
val FMT_WEEKDAY_LONG: DateTimeFormatter = fmt("EEEE, d. MMMM")
val FMT_WEEKDAY_DATE: DateTimeFormatter = fmt("EEEE, d. MMMM yyyy")
val FMT_TASK_DAY: DateTimeFormatter = fmt("EEE, d. MMM")
val FMT_TASK_DAY_YEAR: DateTimeFormatter = fmt("EEE, d. MMM yyyy")
val FMT_DATE_TIME_LONG: DateTimeFormatter = fmt("d. MMMM yyyy, HH:mm")
private val FMT_SPAN_DAY: DateTimeFormatter = fmt("EEE, d. MMM yyyy")
private val FMT_MONTH_SHORT: DateTimeFormatter = fmt("MMM")
private val FMT_MONTH_SHORT_YEAR: DateTimeFormatter = fmt("MMM yyyy")
private val FMT_DAY_TITLE: DateTimeFormatter = fmt("EEE, d. MMMM yyyy")

/** Wochentagsnamen ab [weekStart], z. B. „Mo“, „Di“, … */
fun weekdayNames(weekStart: DayOfWeek, style: TextStyle = TextStyle.SHORT): List<String> =
    (0L until 7L).map { weekStart.plus(it).getDisplayName(style, LOCALE).removeSuffix(".") }

fun DayOfWeek.shortName(): String = getDisplayName(TextStyle.SHORT, LOCALE).removeSuffix(".")

/** Sichtbarer Zeitraum einer Ansicht; `end` ist exklusiv. Monat: volle Wochen. */
data class DateRange(val start: LocalDate, val end: LocalDate) {
    val days: List<LocalDate> get() = generateSequence(start) { it.plusDays(1) }.takeWhile { it < end }.toList()
}

fun viewRange(mode: ViewMode, date: LocalDate, weekStart: DayOfWeek): DateRange = when (mode) {
    ViewMode.MONTH -> {
        val start = startOfWeek(date.withDayOfMonth(1), weekStart)
        val last = date.withDayOfMonth(date.lengthOfMonth())
        val weeks = ((last.toEpochDay() - start.toEpochDay()) / 7) + 1
        DateRange(start, start.plusWeeks(weeks))
    }
    ViewMode.WEEK -> startOfWeek(date, weekStart).let { DateRange(it, it.plusDays(7)) }
    ViewMode.DAY -> DateRange(date, date.plusDays(1))
    ViewMode.LIST -> DateRange(date, date.plusDays(LIST_DAYS))
}

fun viewTitle(mode: ViewMode, date: LocalDate, range: DateRange): UiText = when (mode) {
    ViewMode.MONTH -> date.format(FMT_MONTH_YEAR).capitalized().toUiText()
    ViewMode.WEEK -> {
        val last = range.end.minusDays(1)
        val label = if (range.start.month == last.month) range.start.format(FMT_MONTH_YEAR).capitalized().toUiText()
        else uiText(R.string.month_range, range.start.format(FMT_MONTH_SHORT), last.format(FMT_MONTH_SHORT_YEAR))
        uiText(R.string.view_title_week, label, isoWeek(range.start))
    }
    ViewMode.DAY -> date.format(FMT_DAY_TITLE).toUiText()
    ViewMode.LIST -> uiText(R.string.view_title_list, date.format(FMT_DATE_LONG))
}

private fun String.capitalized() = replaceFirstChar { it.uppercase(LOCALE) }

/** Nächster Zeitraum in Richtung [dir] (±1) */
fun stepDate(mode: ViewMode, date: LocalDate, dir: Int): LocalDate = when (mode) {
    ViewMode.MONTH -> date.plusMonths(dir.toLong())
    ViewMode.WEEK -> date.plusWeeks(dir.toLong())
    ViewMode.DAY -> date.plusDays(dir.toLong())
    ViewMode.LIST -> date.plusDays(30L * dir)
}

/** Lesbarer Zeitraum eines Termins, z. B. „Mo., 23. Nov. 2026 · 09:00 – 10:00“; [end] ist exklusiv. */
fun describeSpan(start: LocalDateTime, end: LocalDateTime, allDay: Boolean): UiText {
    fun day(d: LocalDate) = d.format(FMT_SPAN_DAY)
    if (allDay) {
        val last = end.toLocalDate().minusDays(1)
        return if (last <= start.toLocalDate()) uiText(R.string.span_all_day, day(start.toLocalDate()))
        else uiText(R.string.span_days, day(start.toLocalDate()), day(last))
    }
    return if (start.toLocalDate() == end.toLocalDate())
        uiText(R.string.span_same_day, day(start.toLocalDate()), start.format(FMT_TIME), end.format(FMT_TIME))
    else uiText(
        R.string.span_multi_day,
        day(start.toLocalDate()), start.format(FMT_TIME), day(end.toLocalDate()), end.format(FMT_TIME),
    )
}
