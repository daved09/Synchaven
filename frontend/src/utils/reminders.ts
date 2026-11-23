/**
 * Erinnerungen: Minuten vor Beginn. Bei ganztägigen Terminen bezieht sich der Wert auf
 * Mitternacht; negative Werte sind eine Uhrzeit am Tag selbst (-540 = 09:00).
 */

export const MAX_REMINDERS = 5
export const MAX_BEFORE = 4 * 7 * 24 * 60

const DAY = 1440

/** Voreinstellung neuer Kalender: 15 Minuten vorher bzw. ganztägig am Tag selbst um 09:00 */
export const DEFAULT_TIMED = [15]
export const DEFAULT_ALL_DAY = [-540]

/** Vorschläge für Termine mit Uhrzeit */
export const TIMED_PRESETS = [0, 5, 10, 15, 30, 60, 120, DAY, 2 * DAY, 7 * DAY]

/** Minuten für „`daysBefore` Tage vorher um `hh:mm`“ bei ganztägigen Terminen */
export const allDayMinutes = (daysBefore: number, hour: number, minute = 0) => daysBefore * DAY - (hour * 60 + minute)

/** Vorschläge für ganztägige Termine */
export const ALL_DAY_PRESETS = [
  allDayMinutes(0, 9),
  allDayMinutes(1, 9),
  allDayMinutes(1, 18),
  allDayMinutes(2, 9),
  allDayMinutes(7, 9),
]

function plural(n: number, one: string, many: string) {
  return `${n} ${n === 1 ? one : many}`
}

/** Zerlegt eine Erinnerung an einen ganztägigen Termin in Tage vorher und Uhrzeit. */
export function splitAllDay(minutes: number): { days: number; hour: number; minute: number } {
  const days = Math.max(0, Math.ceil(minutes / DAY))
  const ofDay = days * DAY - minutes
  return { days, hour: Math.floor(ofDay / 60), minute: ofDay % 60 }
}

export function describeReminder(minutes: number, allDay: boolean): string {
  if (allDay) {
    const { days, hour, minute } = splitAllDay(minutes)
    const time = `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
    if (days === 0) return `Am selben Tag um ${time}`
    if (days === 1) return `Am Vortag um ${time}`
    if (days % 7 === 0) return `${plural(days / 7, 'Woche', 'Wochen')} vorher um ${time}`
    return `${days} Tage vorher um ${time}`
  }
  if (minutes === 0) return 'Zum Beginn'
  if (minutes % (7 * DAY) === 0) return `${plural(minutes / (7 * DAY), 'Woche', 'Wochen')} vorher`
  if (minutes % DAY === 0) return `${plural(minutes / DAY, 'Tag', 'Tage')} vorher`
  if (minutes % 60 === 0) return `${plural(minutes / 60, 'Stunde', 'Stunden')} vorher`
  return `${plural(minutes, 'Minute', 'Minuten')} vorher`
}

/** Gültig für die Art des Termins? */
export function isValidReminder(minutes: number, allDay: boolean): boolean {
  return Number.isInteger(minutes) && minutes <= MAX_BEFORE && minutes >= (allDay ? -(DAY - 1) : 0)
}

export const sortReminders = (list: number[]) => [...new Set(list)].sort((a, b) => a - b)
