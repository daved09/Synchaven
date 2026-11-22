package de.dave.synchaven.util

import androidx.annotation.StringRes
import de.dave.synchaven.R
import de.dave.synchaven.UiText
import de.dave.synchaven.toUiText
import de.dave.synchaven.uiText
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.TextStyle

// Formularmodell für RFC-5545-Wiederholungsregeln und deutsche Beschreibung (wie utils/rrule.ts im Web).

enum class Freq(@StringRes val labelRes: Int) {
    NONE(R.string.freq_none), DAILY(R.string.freq_daily), WEEKLY(R.string.freq_weekly), MONTHLY(R.string.freq_monthly),
    YEARLY(R.string.freq_yearly), CUSTOM(R.string.freq_custom),
}

enum class MonthlyMode { DAY, WEEKDAY }
enum class EndMode { NEVER, UNTIL, COUNT }

data class RecurrenceForm(
    val freq: Freq = Freq.NONE,
    val interval: Int = 1,
    val byDay: Set<DayOfWeek> = emptySet(),
    val monthlyMode: MonthlyMode = MonthlyMode.DAY,
    val endMode: EndMode = EndMode.NEVER,
    val until: LocalDate? = null,
    val count: Int = 10,
    val raw: String = "",
)

private val CODES = listOf("MO", "TU", "WE", "TH", "FR", "SA", "SU")

fun DayOfWeek.code(): String = CODES[value - 1]
private fun dayFromCode(code: String): DayOfWeek? = CODES.indexOf(code).takeIf { it >= 0 }?.let { DayOfWeek.of(it + 1) }

/** Ordnungszahl des Wochentags im Monat: 1..4 oder -1 (letzter) */
fun weekdayOrdinal(d: LocalDate): Int {
    val n = (d.dayOfMonth + 6) / 7
    return if (d.dayOfMonth + 7 > d.lengthOfMonth() && n >= 4) -1 else n
}

fun emptyRecurrence(start: LocalDate) = RecurrenceForm(byDay = setOf(start.dayOfWeek))

private fun parts(rule: String): Map<String, String> =
    rule.replace(Regex("^RRULE:", RegexOption.IGNORE_CASE), "").split(';').mapNotNull { p ->
        val i = p.indexOf('=')
        if (i <= 0) null else p.substring(0, i).trim().uppercase() to p.substring(i + 1).trim().uppercase()
    }.toMap()

private val UNTIL_RE = Regex("""^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})Z?)?$""")

/** UNTIL (bei Uhrzeit mit Z: UTC) in ein lokales Datum umwandeln */
private fun untilToDate(v: String): LocalDate? {
    val m = UNTIL_RE.matchEntire(v) ?: return null
    val (y, mo, d) = m.destructured
    val date = LocalDate.of(y.toInt(), mo.toInt(), d.toInt())
    if (m.groupValues[4].isEmpty() || !v.endsWith("Z")) return date
    val utc = LocalDateTime.of(date.year, date.month, date.dayOfMonth,
        m.groupValues[4].toInt(), m.groupValues[5].toInt(), m.groupValues[6].toInt())
    return utc.toInstant(ZoneOffset.UTC).toLocal().toLocalDate()
}

private val SUPPORTED = setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH", "COUNT", "UNTIL", "WKST")
private val MONTHLY_BYDAY = Regex("""^(-?\d)([A-Z]{2})$""")

fun parseRRule(rule: String?, start: LocalDate): RecurrenceForm {
    var form = emptyRecurrence(start)
    if (rule.isNullOrBlank()) return form
    val p = parts(rule)
    form = form.copy(raw = rule)
    val freq = p["FREQ"]?.let { f -> Freq.entries.firstOrNull { it.name == f } }
    if (freq == null || freq == Freq.NONE || freq == Freq.CUSTOM || p.keys.any { it !in SUPPORTED }) {
        return form.copy(freq = Freq.CUSTOM)
    }
    form = form.copy(freq = freq, interval = (p["INTERVAL"]?.toIntOrNull() ?: 1).coerceAtLeast(1))
    val byDay = p["BYDAY"]
    if (freq == Freq.WEEKLY && byDay != null) {
        form = form.copy(byDay = byDay.split(',').mapNotNull { dayFromCode(it) }.toSet())
    }
    if (freq == Freq.MONTHLY && byDay != null) {
        form = if (byDay.contains(',') || !MONTHLY_BYDAY.matches(byDay)) form.copy(freq = Freq.CUSTOM)
        else form.copy(monthlyMode = MonthlyMode.WEEKDAY)
    }
    if ((freq == Freq.DAILY || freq == Freq.YEARLY) && byDay != null) form = form.copy(freq = Freq.CUSTOM)
    p["UNTIL"]?.let { form = form.copy(endMode = EndMode.UNTIL, until = untilToDate(it)) }
        ?: p["COUNT"]?.let { form = form.copy(endMode = EndMode.COUNT, count = it.toIntOrNull() ?: 1) }
    return form
}

fun buildRRule(form: RecurrenceForm, start: LocalDate): String? {
    if (form.freq == Freq.NONE) return null
    if (form.freq == Freq.CUSTOM) {
        return form.raw.trim().replace(Regex("^RRULE:", RegexOption.IGNORE_CASE), "").ifBlank { null }
    }
    val r = mutableListOf("FREQ=${form.freq.name}")
    if (form.interval > 1) r += "INTERVAL=${form.interval}"
    if (form.freq == Freq.WEEKLY) {
        val days = DayOfWeek.entries.filter { it in form.byDay }.ifEmpty { listOf(start.dayOfWeek) }
        r += "BYDAY=" + days.joinToString(",") { it.code() }
    }
    if (form.freq == Freq.MONTHLY) {
        r += if (form.monthlyMode == MonthlyMode.WEEKDAY) "BYDAY=${weekdayOrdinal(start)}${start.dayOfWeek.code()}"
        else "BYMONTHDAY=${start.dayOfMonth}"
    }
    if (form.endMode == EndMode.UNTIL && form.until != null) r += "UNTIL=" + form.until.toString().replace("-", "")
    if (form.endMode == EndMode.COUNT) r += "COUNT=${form.count.coerceAtLeast(1)}"
    return r.joinToString(";")
}

/** „letzten“ bzw. „2.“ für BYDAY=-1MO / 2MO */
fun ordinalText(n: Int): UiText = if (n == -1) uiText(R.string.ordinal_last) else uiText(R.string.ordinal_n, n)

private fun longName(d: DayOfWeek) = d.getDisplayName(TextStyle.FULL, LOCALE)

/** Deutsche Kurzbeschreibung, z. B. „Alle 2 Wochen am Mo, Mi bis 31.12.2026“ */
fun describeRRule(rule: String?, start: LocalDate): UiText {
    if (rule.isNullOrBlank()) return UiText.Plain("")
    val p = parts(rule)
    val n = p["INTERVAL"]?.toIntOrNull() ?: 1
    val byDay = p["BYDAY"]
    val text: UiText = when (p["FREQ"]) {
        "DAILY" -> UiText.Plural(R.plurals.rrule_daily, n, listOf(n))
        "WEEKLY" -> {
            val days = (byDay ?: start.dayOfWeek.code()).split(',')
                .map { (dayFromCode(it)?.shortName() ?: it).toUiText() }
            UiText.Plural(R.plurals.rrule_weekly, n, listOf(n, UiText.Join(days)))
        }
        "MONTHLY" -> {
            val m = byDay?.let { MONTHLY_BYDAY.matchEntire(it) }
            val on = if (m != null) {
                val day = dayFromCode(m.groupValues[2])?.let(::longName) ?: m.groupValues[2]
                uiText(R.string.rrule_monthly_on_weekday, ordinalText(m.groupValues[1].toInt()), day)
            } else uiText(R.string.rrule_monthly_on_day, p["BYMONTHDAY"]?.toIntOrNull() ?: start.dayOfMonth)
            UiText.Plural(R.plurals.rrule_monthly, n, listOf(n, on))
        }
        "YEARLY" -> UiText.Plural(R.plurals.rrule_yearly, n, listOf(n, start.format(FMT_DAY_MONTH)))
        else -> return uiText(R.string.rrule_raw, rule)
    }
    val until = p["UNTIL"]?.let { untilToDate(it) }
    val count = p["COUNT"]
    return when {
        until != null -> uiText(R.string.rrule_until, text, until.format(FMT_DATE_SHORT))
        p["UNTIL"] == null && count != null -> uiText(R.string.rrule_count, text, count)
        else -> text
    }
}
