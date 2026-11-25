package de.dave.synchaven.util

import android.text.format.DateFormat
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.data.store.ViewMode
import de.dave.synchaven.toUiText
import de.dave.synchaven.uiText
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

// Date helpers – always displayed in the device's time zone and language.

/**
 * Locale for dates, sorting and case conversion: the app's current language (device language or the
 * language chosen for the app in the Android settings). Read on every use, since it can change while the app runs.
 */
val LOCALE: Locale get() = Locale.getDefault()

/** Number of days of the event list from the selected date */
const val LIST_DAYS = 60L

fun deviceZone(): ZoneId = ZoneId.systemDefault()

/** RFC 3339 point in time with any offset → Instant */
fun parseInstant(s: String): Instant = OffsetDateTime.parse(s).toInstant()

fun Instant.toLocal(zone: ZoneId = deviceZone()): LocalDateTime = atZone(zone).toLocalDateTime()

fun LocalDateTime.toInstant(zone: ZoneId = deviceZone()): Instant = atZone(zone).toInstant()

fun LocalDate.startInstant(zone: ZoneId = deviceZone()): Instant = atStartOfDay(zone).toInstant()

/** All-day events are transferred as midnight UTC; only the date part counts. */
fun allDayToLocal(iso: String): LocalDate = LocalDate.parse(iso.substring(0, 10))

fun localToAllDay(d: LocalDate): String = "${d}T00:00:00Z"

/** Point in time with the device time zone's offset, e.g. `2026-11-26T14:30:00+01:00` */
fun LocalDateTime.toOffsetString(zone: ZoneId = deviceZone()): String =
    atZone(zone).toOffsetDateTime().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

fun startOfWeek(d: LocalDate, weekStart: DayOfWeek): LocalDate =
    d.with(TemporalAdjusters.previousOrSame(weekStart))

fun isoWeek(d: LocalDate): Int = d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

/**
 * Formatter for a CLDR skeleton (which fields, not their order), e.g. `yMMMEd` → "Mon, Nov 23, 2026" in English
 * and "Mo., 23. Nov. 2026" in German. The pattern comes from Android's locale data; cached per locale.
 */
fun dateFormat(skeleton: String, locale: Locale = LOCALE): DateTimeFormatter =
    formatters.getOrPut(locale to skeleton) {
        runCatching { DateTimeFormatter.ofPattern(datePattern(skeleton, locale), locale) }
            .getOrElse { DateTimeFormatter.ofPattern(FallbackPatterns.of(locale, skeleton), locale) }
    }

/** Best pattern for [skeleton] in [locale]; on the JVM (unit tests) there is no Android locale data. */
private fun datePattern(skeleton: String, locale: Locale): String =
    runCatching { DateFormat.getBestDateTimePattern(locale, skeleton) }.getOrNull()
        ?: FallbackPatterns.of(locale, skeleton)

private val formatters = ConcurrentHashMap<Pair<Locale, String>, DateTimeFormatter>()

/**
 * Patterns without Android's locale data (JVM unit tests) – German for German, otherwise English (US),
 * the same as Android returns for these locales.
 */
private object FallbackPatterns {
    private val GERMAN = mapOf(
        "jm" to "HH:mm", "j" to "HH 'Uhr'", "yMMdd" to "dd.MM.yyyy", "yMMMMd" to "d. MMMM yyyy", "MMMMd" to "d. MMMM",
        "yMMMM" to "MMMM yyyy", "MMMMEEEEd" to "EEEE, d. MMMM", "yMMMMEEEEd" to "EEEE, d. MMMM yyyy", "MMMEd" to "EEE, d. MMM",
        "yMMMEd" to "EEE, d. MMM yyyy", "yMMMMdjm" to "d. MMMM yyyy, HH:mm", "MMM" to "MMM", "yMMM" to "MMM yyyy",
        "yMMMMEd" to "EEE, d. MMMM yyyy",
    )
    private val ENGLISH = mapOf(
        "jm" to "h:mm a", "j" to "h a", "yMMdd" to "MM/dd/yyyy", "yMMMMd" to "MMMM d, yyyy", "MMMMd" to "MMMM d",
        "yMMMM" to "MMMM yyyy", "MMMMEEEEd" to "EEEE, MMMM d", "yMMMMEEEEd" to "EEEE, MMMM d, yyyy", "MMMEd" to "EEE, MMM d",
        "yMMMEd" to "EEE, MMM d, yyyy", "yMMMMdjm" to "MMMM d, yyyy, h:mm a", "MMM" to "MMM", "yMMM" to "MMM yyyy",
        "yMMMMEd" to "EEE, MMMM d, yyyy",
    )

    fun of(locale: Locale, skeleton: String): String =
        (if (locale.language == "de") GERMAN else ENGLISH)[skeleton] ?: error("No fallback pattern for $skeleton")
}

/** Time of day, e.g. "14:30" or "2:30 PM" */
val FMT_TIME: DateTimeFormatter get() = dateFormat("jm")
/** Numeric date, e.g. "31.12.2026" or "12/31/2026" */
val FMT_DATE_SHORT: DateTimeFormatter get() = dateFormat("yMMdd")
val FMT_DATE_LONG: DateTimeFormatter get() = dateFormat("yMMMMd")
val FMT_DAY_MONTH: DateTimeFormatter get() = dateFormat("MMMMd")
val FMT_MONTH_YEAR: DateTimeFormatter get() = dateFormat("yMMMM")
val FMT_WEEKDAY_LONG: DateTimeFormatter get() = dateFormat("MMMMEEEEd")
val FMT_WEEKDAY_DATE: DateTimeFormatter get() = dateFormat("yMMMMEEEEd")
/** Short day without year, e.g. "Fr., 4. Dez." or "Fri, Dec 4" */
val FMT_TASK_DAY: DateTimeFormatter get() = dateFormat("MMMEd")
val FMT_TASK_DAY_YEAR: DateTimeFormatter get() = dateFormat("yMMMEd")
val FMT_DATE_TIME_LONG: DateTimeFormatter get() = dateFormat("yMMMMdjm")
private val FMT_SPAN_DAY: DateTimeFormatter get() = FMT_TASK_DAY_YEAR
private val FMT_MONTH_SHORT: DateTimeFormatter get() = dateFormat("MMM")
private val FMT_MONTH_SHORT_YEAR: DateTimeFormatter get() = dateFormat("yMMM")
private val FMT_DAY_TITLE: DateTimeFormatter get() = dateFormat("yMMMMEd")

/** Label of an hour in the time grid: "09:00" with a 24-hour clock, "9 AM" with a 12-hour clock */
fun hourLabel(hour: Int): String {
    val time = LocalTime.of(hour, 0)
    return if (uses24HourClock()) time.format(FMT_TIME) else time.format(dateFormat("j"))
}

fun uses24HourClock(locale: Locale = LOCALE): Boolean = datePattern("jm", locale).let { 'H' in it || 'k' in it }

/** Weekday names from [weekStart], e.g. "Mo", "Di", … */
fun weekdayNames(weekStart: DayOfWeek, style: TextStyle = TextStyle.SHORT): List<String> =
    (0L until 7L).map { weekStart.plus(it).getDisplayName(style, LOCALE).removeSuffix(".") }

fun DayOfWeek.shortName(): String = getDisplayName(TextStyle.SHORT, LOCALE).removeSuffix(".")

/** Visible range of a view; `end` is exclusive. Month: whole weeks. */
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

/** Next range in direction [dir] (±1) */
fun stepDate(mode: ViewMode, date: LocalDate, dir: Int): LocalDate = when (mode) {
    ViewMode.MONTH -> date.plusMonths(dir.toLong())
    ViewMode.WEEK -> date.plusWeeks(dir.toLong())
    ViewMode.DAY -> date.plusDays(dir.toLong())
    ViewMode.LIST -> date.plusDays(30L * dir)
}

/** Readable time range of an event, e.g. "Mo., 23. Nov. 2026 · 09:00 – 10:00"; [end] is exclusive. */
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
