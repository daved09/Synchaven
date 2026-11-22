package de.dave.synchaven.util

import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Platzierung von Terminen in Wochenzeilen (Monat, Ganztägig-Leiste) und Tagesspalten.

data class RowItem(
    val ev: ViewEvent,
    /** Spalte (inklusive) */
    val start: Int,
    /** Spalte (exklusive) */
    val end: Int,
    var lane: Int = 0,
    val contBefore: Boolean,
    val contAfter: Boolean,
)

private fun daysBetween(a: LocalDate, b: LocalDate) = ChronoUnit.DAYS.between(a, b).toInt()

/** Verteilt Termine auf Spuren einer Zeile aus [n] Tagen ab [rowStart]. */
fun layoutRow(events: List<ViewEvent>, rowStart: LocalDate, n: Int): List<RowItem> {
    val rowEnd = rowStart.plusDays(n.toLong())
    val items = events
        .filter { it.start.toLocalDate() < rowEnd && it.lastDay >= rowStart }
        .map { ev ->
            val start = maxOf(0, daysBetween(rowStart, ev.start.toLocalDate()))
            val end = minOf(n, daysBetween(rowStart, ev.lastDay) + 1)
            RowItem(
                ev = ev, start = start, end = maxOf(end, start + 1),
                contBefore = ev.start.toLocalDate() < rowStart,
                contAfter = ev.lastDay >= rowEnd,
            )
        }
        .sortedWith(
            compareBy<RowItem> { it.start }
                .thenByDescending { it.end - it.start }
                .thenByDescending { it.ev.isBar }
                .thenBy { it.ev.start }
        )
    val lanes = mutableListOf<Int>()
    for (it in items) {
        var lane = lanes.indexOfFirst { end -> end <= it.start }
        if (lane == -1) {
            lane = lanes.size
            lanes.add(it.end)
        } else {
            lanes[lane] = it.end
        }
        it.lane = lane
    }
    return items
}

class ClampedRow(val visible: List<RowItem>, val more: IntArray)

/**
 * Begrenzt die Zeile auf [maxLanes] Spuren. Liefert die sichtbaren Einträge und je Tag
 * die Anzahl ausgeblendeter Termine (für „+N“).
 */
fun clampRow(items: List<RowItem>, n: Int, maxLanes: Int): ClampedRow {
    val hidden = items.filter { it.lane >= maxLanes }.toMutableSet()
    // Die letzte Spur macht Platz für „+N“, wo an einem Tag etwas ausgeblendet ist
    for (iteration in 0 until 5) {
        val overflowDays = hidden.flatMap { it.start until it.end }.toSet()
        var changed = false
        for (i in items) {
            if (i.lane == maxLanes - 1 && i !in hidden && (i.start until i.end).any { it in overflowDays }) {
                hidden.add(i)
                changed = true
            }
        }
        if (!changed) break
    }
    val more = IntArray(n)
    for (i in hidden) for (d in i.start until i.end) more[d]++
    return ClampedRow(items.filterNot { it in hidden }, more)
}

data class TimedItem(
    val ev: ViewEvent,
    /** Minuten ab Tagesbeginn */
    val top: Int,
    val height: Int,
    var col: Int = 0,
    var cols: Int = 1,
)

/** Überlappende Termine eines Tages nebeneinander anordnen. */
fun layoutDay(events: List<ViewEvent>, day: LocalDate, minHeight: Int = 20): List<TimedItem> {
    val dayStart = day.atStartOfDay()
    val dayEnd = dayStart.plusDays(1)
    val segs = events
        .filter { !it.isBar }
        .filter { e -> e.start < dayEnd && (e.end > dayStart || (e.end == e.start && e.start >= dayStart)) }
        .map { ev ->
            val s = maxOf(ev.start, dayStart)
            val e = minOf(ev.end, dayEnd)
            TimedItem(
                ev = ev,
                top = Duration.between(dayStart, s).toMinutes().toInt(),
                height = maxOf(minHeight, Duration.between(s, e).toMinutes().toInt()),
            )
        }
        .sortedWith(compareBy<TimedItem> { it.top }.thenByDescending { it.height })

    var cluster = mutableListOf<TimedItem>()
    var colEnds = mutableListOf<Int>()
    var clusterEnd = -1
    fun flush() {
        for (it in cluster) it.cols = colEnds.size
        cluster = mutableListOf()
        colEnds = mutableListOf()
    }
    for (it in segs) {
        if (it.top >= clusterEnd) flush()
        var col = colEnds.indexOfFirst { end -> end <= it.top }
        if (col == -1) {
            col = colEnds.size
            colEnds.add(it.top + it.height)
        } else {
            colEnds[col] = it.top + it.height
        }
        it.col = col
        cluster.add(it)
        clusterEnd = maxOf(clusterEnd, it.top + it.height)
    }
    flush()
    return segs
}
