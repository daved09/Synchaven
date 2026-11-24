// Form model for RFC 5545 recurrence rules and German description.
import { fmt, fmtDateShort } from './date'

export type Freq = 'NONE' | 'DAILY' | 'WEEKLY' | 'MONTHLY' | 'YEARLY' | 'CUSTOM'
export const WEEKDAYS = ['MO', 'TU', 'WE', 'TH', 'FR', 'SA', 'SU'] as const
export const WEEKDAY_LABELS: Record<string, string> = {
  MO: 'Montag', TU: 'Dienstag', WE: 'Mittwoch', TH: 'Donnerstag', FR: 'Freitag', SA: 'Samstag', SU: 'Sonntag',
}
const SHORT: Record<string, string> = { MO: 'Mo', TU: 'Di', WE: 'Mi', TH: 'Do', FR: 'Fr', SA: 'Sa', SU: 'So' }

export interface RecurrenceForm {
  freq: Freq
  interval: number
  byday: string[]
  monthlyMode: 'day' | 'weekday'
  endMode: 'never' | 'until' | 'count'
  until: string // YYYY-MM-DD
  count: number
  raw: string
}

export const jsDayToCode = (d: Date) => WEEKDAYS[(d.getDay() + 6) % 7]
/** Ordinal of the weekday in the month: 1..4 or -1 (last) */
export function weekdayOrdinal(d: Date): number {
  const n = Math.ceil(d.getDate() / 7)
  const daysInMonth = new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()
  return d.getDate() + 7 > daysInMonth && n >= 4 ? -1 : n
}

export function emptyForm(start: Date): RecurrenceForm {
  return {
    freq: 'NONE', interval: 1, byday: [jsDayToCode(start)], monthlyMode: 'day',
    endMode: 'never', until: '', count: 10, raw: '',
  }
}

function parts(rule: string): Map<string, string> {
  const m = new Map<string, string>()
  for (const p of rule.replace(/^RRULE:/i, '').split(';')) {
    const [k, v] = p.split('=')
    if (k && v !== undefined) m.set(k.trim().toUpperCase(), v.trim().toUpperCase())
  }
  return m
}

/** Convert UNTIL (UTC) to a local date YYYY-MM-DD */
function untilToDate(v: string): string {
  const m = /^(\d{4})(\d{2})(\d{2})(?:T(\d{2})(\d{2})(\d{2})Z?)?$/.exec(v)
  if (!m) return ''
  if (!m[4] || !v.endsWith('Z')) return `${m[1]}-${m[2]}-${m[3]}`
  const d = new Date(Date.UTC(+m[1], +m[2] - 1, +m[3], +m[4], +m[5], +m[6]))
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function parseRRule(rule: string | null, start: Date): RecurrenceForm {
  const form = emptyForm(start)
  if (!rule) return form
  const p = parts(rule)
  form.raw = rule
  const supported = new Set(['FREQ', 'INTERVAL', 'BYDAY', 'BYMONTHDAY', 'BYMONTH', 'COUNT', 'UNTIL', 'WKST'])
  const freq = p.get('FREQ') as Freq | undefined
  if (!freq || !['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'].includes(freq) || [...p.keys()].some((k) => !supported.has(k))) {
    form.freq = 'CUSTOM'
    return form
  }
  form.freq = freq
  form.interval = Math.max(1, parseInt(p.get('INTERVAL') ?? '1') || 1)
  const byday = p.get('BYDAY')
  if (freq === 'WEEKLY' && byday) form.byday = byday.split(',').filter((d) => WEEKDAYS.includes(d as never))
  if (freq === 'MONTHLY' && byday) {
    if (byday.includes(',') || !/^-?\d[A-Z]{2}$/.test(byday)) form.freq = 'CUSTOM'
    else form.monthlyMode = 'weekday'
  }
  if ((freq === 'DAILY' || freq === 'YEARLY') && byday) form.freq = 'CUSTOM'
  if (p.has('UNTIL')) {
    form.endMode = 'until'
    form.until = untilToDate(p.get('UNTIL')!)
  } else if (p.has('COUNT')) {
    form.endMode = 'count'
    form.count = parseInt(p.get('COUNT')!) || 1
  }
  return form
}

export function buildRRule(form: RecurrenceForm, start: Date): string | null {
  if (form.freq === 'NONE') return null
  if (form.freq === 'CUSTOM') return form.raw.trim().replace(/^RRULE:/i, '') || null
  const r = [`FREQ=${form.freq}`]
  if (form.interval > 1) r.push(`INTERVAL=${Math.floor(form.interval)}`)
  if (form.freq === 'WEEKLY') {
    const days = WEEKDAYS.filter((d) => form.byday.includes(d))
    r.push(`BYDAY=${(days.length ? days : [jsDayToCode(start)]).join(',')}`)
  }
  if (form.freq === 'MONTHLY') {
    r.push(form.monthlyMode === 'weekday'
      ? `BYDAY=${weekdayOrdinal(start)}${jsDayToCode(start)}`
      : `BYMONTHDAY=${start.getDate()}`)
  }
  if (form.endMode === 'until' && form.until) r.push(`UNTIL=${form.until.replace(/-/g, '')}`)
  if (form.endMode === 'count') r.push(`COUNT=${Math.max(1, Math.floor(form.count))}`)
  return r.join(';')
}

const ordinalText = (n: number) => (n === -1 ? 'letzten' : `${n}.`)

/** Short German description, e.g. "Alle 2 Wochen am Mo, Mi bis 31.12.2026" */
export function describeRRule(rule: string | null, start: Date): string {
  if (!rule) return ''
  const p = parts(rule)
  const freq = p.get('FREQ')
  const n = parseInt(p.get('INTERVAL') ?? '1') || 1
  const byday = p.get('BYDAY')
  let text: string
  switch (freq) {
    case 'DAILY':
      text = n === 1 ? 'Täglich' : `Alle ${n} Tage`
      break
    case 'WEEKLY': {
      const days = (byday ?? jsDayToCode(start)).split(',').map((d) => SHORT[d] ?? d).join(', ')
      text = `${n === 1 ? 'Wöchentlich' : `Alle ${n} Wochen`} am ${days}`
      break
    }
    case 'MONTHLY': {
      const m = byday && /^(-?\d)([A-Z]{2})$/.exec(byday)
      const on = m ? `am ${ordinalText(+m[1])} ${WEEKDAY_LABELS[m[2]]}` : `am ${p.get('BYMONTHDAY') ?? start.getDate()}.`
      text = `${n === 1 ? 'Monatlich' : `Alle ${n} Monate`} ${on}`
      break
    }
    case 'YEARLY':
      text = `${n === 1 ? 'Jährlich' : `Alle ${n} Jahre`} am ${fmt(start, { day: 'numeric', month: 'long' })}`
      break
    default:
      return `Wiederholung: ${rule}`
  }
  if (p.has('UNTIL')) {
    const d = untilToDate(p.get('UNTIL')!)
    const [y, mo, da] = d.split('-').map(Number)
    if (y) text += ` bis ${fmtDateShort(new Date(y, mo - 1, da))}`
  } else if (p.has('COUNT')) {
    text += `, ${p.get('COUNT')} Mal`
  }
  return text
}
