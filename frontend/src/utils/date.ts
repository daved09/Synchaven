// Date helpers – all computations in the browser's local time zone.

export const LOCALE = 'de-DE'
export const browserTimeZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

export function startOfDay(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate())
}
export function addDays(d: Date, n: number): Date {
  return new Date(d.getFullYear(), d.getMonth(), d.getDate() + n, d.getHours(), d.getMinutes())
}
export function addMinutes(d: Date, n: number): Date {
  return new Date(d.getTime() + n * 60_000)
}
export function addMonths(d: Date, n: number): Date {
  const r = new Date(d.getFullYear(), d.getMonth() + n, 1)
  const last = new Date(r.getFullYear(), r.getMonth() + 1, 0).getDate()
  r.setDate(Math.min(d.getDate(), last))
  return r
}
export function startOfMonth(d: Date): Date {
  return new Date(d.getFullYear(), d.getMonth(), 1)
}
export function startOfWeek(d: Date, weekStartsOn: number): Date {
  const s = startOfDay(d)
  const diff = (s.getDay() - weekStartsOn + 7) % 7
  return addDays(s, -diff)
}
export function isSameDay(a: Date, b: Date): boolean {
  return a.getFullYear() === b.getFullYear() && a.getMonth() === b.getMonth() && a.getDate() === b.getDate()
}
export function diffDays(a: Date, b: Date): number {
  return Math.round((startOfDay(a).getTime() - startOfDay(b).getTime()) / 86_400_000)
}

const pad = (n: number) => String(n).padStart(2, '0')

/** YYYY-MM-DD in local time */
export function dayKey(d: Date): string {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}
export function parseDayKey(s: string): Date | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(s)
  return m ? new Date(+m[1], +m[2] - 1, +m[3]) : null
}
export function timeKey(d: Date): string {
  return `${pad(d.getHours())}:${pad(d.getMinutes())}`
}
export function combine(date: string, time: string): Date {
  const d = parseDayKey(date) ?? startOfDay(new Date())
  const [h, m] = (time || '00:00').split(':').map(Number)
  d.setHours(h || 0, m || 0, 0, 0)
  return d
}

/** All-day events are transferred as midnight UTC */
export function allDayToLocal(iso: string): Date {
  return new Date(+iso.slice(0, 4), +iso.slice(5, 7) - 1, +iso.slice(8, 10))
}
export function localToAllDay(d: Date): string {
  return `${dayKey(d)}T00:00:00Z`
}

const fmtCache = new Map<string, Intl.DateTimeFormat>()
export function fmt(d: Date, opts: Intl.DateTimeFormatOptions): string {
  const key = JSON.stringify(opts)
  let f = fmtCache.get(key)
  if (!f) fmtCache.set(key, (f = new Intl.DateTimeFormat(LOCALE, opts)))
  return f.format(d)
}
export const fmtTime = (d: Date) => fmt(d, { hour: '2-digit', minute: '2-digit' })
export const fmtDate = (d: Date) => fmt(d, { day: 'numeric', month: 'long', year: 'numeric' })
export const fmtDateShort = (d: Date) => fmt(d, { day: '2-digit', month: '2-digit', year: 'numeric' })
export const fmtWeekdayLong = (d: Date) => fmt(d, { weekday: 'long', day: 'numeric', month: 'long' })

export function weekdayNames(weekStartsOn: number, style: 'short' | 'narrow' | 'long' = 'short'): string[] {
  const base = startOfWeek(new Date(2026, 0, 7), weekStartsOn)
  return Array.from({ length: 7 }, (_, i) => fmt(addDays(base, i), { weekday: style }))
}

/** ISO 8601 calendar week */
export function isoWeek(d: Date): number {
  const t = new Date(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()))
  const day = t.getUTCDay() || 7
  t.setUTCDate(t.getUTCDate() + 4 - day)
  const yearStart = new Date(Date.UTC(t.getUTCFullYear(), 0, 1))
  return Math.ceil(((t.getTime() - yearStart.getTime()) / 86_400_000 + 1) / 7)
}

/** Readable time range of an event, e.g. "Mo., 23. Nov. · 09:00 – 10:00" */
export function describeSpan(start: Date, end: Date, allDay: boolean): string {
  const day = (d: Date) => fmt(d, { weekday: 'short', day: 'numeric', month: 'short', year: 'numeric' })
  if (allDay) {
    const last = addDays(end, -1)
    return isSameDay(start, last) ? `${day(start)} · ganztägig` : `${day(start)} – ${day(last)}`
  }
  if (isSameDay(start, end)) return `${day(start)} · ${fmtTime(start)} – ${fmtTime(end)}`
  return `${day(start)}, ${fmtTime(start)} – ${day(end)}, ${fmtTime(end)}`
}
