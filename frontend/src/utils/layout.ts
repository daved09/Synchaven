// Platzierung von Terminen in Wochenzeilen (Monat, Ganztägig-Leiste) und Tagesspalten.
import type { ViewEvent } from '@/api/types'
import { addDays, diffDays, startOfDay } from './date'

/** Ganztägig oder ≥ 24 h → wird als Balken statt im Zeitraster angezeigt */
export function isBarEvent(e: ViewEvent): boolean {
  return e.all_day || e.endDate.getTime() - e.startDate.getTime() >= 86_400_000
}

export interface RowItem {
  ev: ViewEvent
  /** Spalte (inklusive) */
  start: number
  /** Spalte (exklusive) */
  end: number
  lane: number
  contBefore: boolean
  contAfter: boolean
}

/** Letzter (inklusiver) Tag, an dem der Termin noch zu sehen ist */
export function lastDay(e: ViewEvent): Date {
  if (e.all_day) return addDays(e.endDate, -1)
  if (e.endDate.getTime() === e.startDate.getTime()) return e.startDate
  return new Date(e.endDate.getTime() - 1)
}

/** Verteilt Termine auf Spuren einer Zeile aus `n` Tagen ab `rowStart`. */
export function layoutRow(events: ViewEvent[], rowStart: Date, n: number): RowItem[] {
  const rowEnd = addDays(rowStart, n)
  const items = events
    .filter((e) => e.startDate < rowEnd && lastDay(e) >= rowStart)
    .map((ev) => {
      const start = Math.max(0, diffDays(ev.startDate, rowStart))
      const end = Math.min(n, diffDays(lastDay(ev), rowStart) + 1)
      return {
        ev, start, end: Math.max(end, start + 1), lane: 0,
        contBefore: startOfDay(ev.startDate) < rowStart,
        contAfter: lastDay(ev) >= rowEnd,
      }
    })
    .sort((a, b) =>
      a.start - b.start
      || (b.end - b.start) - (a.end - a.start)
      || Number(isBarEvent(b.ev)) - Number(isBarEvent(a.ev))
      || a.ev.startDate.getTime() - b.ev.startDate.getTime(),
    )
  const lanes: number[] = []
  for (const it of items) {
    let lane = lanes.findIndex((end) => end <= it.start)
    if (lane === -1) lane = lanes.length
    lanes[lane] = it.end
    it.lane = lane
  }
  return items
}

/**
 * Begrenzt die Zeile auf `maxLanes` Spuren. Liefert die sichtbaren Einträge und je Tag
 * die Anzahl ausgeblendeter Termine (für "+N weitere").
 */
export function clampRow(items: RowItem[], n: number, maxLanes: number) {
  const hidden = new Set<RowItem>(items.filter((i) => i.lane >= maxLanes))
  for (let iter = 0; iter < 5; iter++) {
    const overflowDays = new Set<number>()
    for (const i of hidden) for (let d = i.start; d < i.end; d++) overflowDays.add(d)
    let changed = false
    for (const i of items) {
      if (i.lane === maxLanes - 1 && !hidden.has(i)) {
        for (let d = i.start; d < i.end; d++) {
          if (overflowDays.has(d)) {
            hidden.add(i)
            changed = true
            break
          }
        }
      }
    }
    if (!changed) break
  }
  const more = Array.from({ length: n }, () => 0)
  for (const i of hidden) for (let d = i.start; d < i.end; d++) more[d]++
  return { visible: items.filter((i) => !hidden.has(i)), more }
}

export interface TimedItem {
  ev: ViewEvent
  /** Minuten ab Tagesbeginn */
  top: number
  height: number
  col: number
  cols: number
  contBefore: boolean
  contAfter: boolean
}

/** Überlappende Termine eines Tages nebeneinander anordnen. */
export function layoutDay(events: ViewEvent[], day: Date, minHeight = 20): TimedItem[] {
  const dayStart = startOfDay(day)
  const dayEnd = addDays(dayStart, 1)
  const segs = events
    .filter((e) => !isBarEvent(e))
    .filter((e) => e.startDate < dayEnd && (e.endDate > dayStart || (e.endDate.getTime() === e.startDate.getTime() && e.startDate >= dayStart)))
    .map((ev) => {
      const s = Math.max(ev.startDate.getTime(), dayStart.getTime())
      const e = Math.min(ev.endDate.getTime(), dayEnd.getTime())
      const top = (s - dayStart.getTime()) / 60_000
      return {
        ev, top, height: Math.max(minHeight, (e - s) / 60_000), col: 0, cols: 1,
        contBefore: ev.startDate < dayStart, contAfter: ev.endDate > dayEnd,
      }
    })
    .sort((a, b) => a.top - b.top || b.height - a.height)

  let cluster: TimedItem[] = []
  let colEnds: number[] = []
  let clusterEnd = -1
  const flush = () => {
    for (const it of cluster) it.cols = colEnds.length
    cluster = []
    colEnds = []
  }
  for (const it of segs) {
    if (it.top >= clusterEnd) flush()
    let col = colEnds.findIndex((end) => end <= it.top)
    if (col === -1) col = colEnds.length
    colEnds[col] = it.top + it.height
    it.col = col
    cluster.push(it)
    clusterEnd = Math.max(clusterEnd, it.top + it.height)
  }
  flush()
  return segs
}
