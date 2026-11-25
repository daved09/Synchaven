import { t } from '@/i18n'
/**
 * Reminders: minutes before the start. For all-day events the value refers to
 * midnight; negative values are a time on the day itself (-540 = 09:00).
 */

export const MAX_REMINDERS = 5
export const MAX_BEFORE = 4 * 7 * 24 * 60

const DAY = 1440

/** Preset for new calendars: 15 minutes before, or all-day on the day itself at 09:00 */
export const DEFAULT_TIMED = [15]
export const DEFAULT_ALL_DAY = [-540]

/** Suggestions for timed events */
export const TIMED_PRESETS = [0, 5, 10, 15, 30, 60, 120, DAY, 2 * DAY, 7 * DAY]

/** Minutes for "`daysBefore` days before at `hh:mm`" for all-day events */
export const allDayMinutes = (daysBefore: number, hour: number, minute = 0) => daysBefore * DAY - (hour * 60 + minute)

/** Suggestions for all-day events */
export const ALL_DAY_PRESETS = [
  allDayMinutes(0, 9),
  allDayMinutes(1, 9),
  allDayMinutes(1, 18),
  allDayMinutes(2, 9),
  allDayMinutes(7, 9),
]

/** Splits a reminder for an all-day event into days before and time. */
export function splitAllDay(minutes: number): { days: number; hour: number; minute: number } {
  const days = Math.max(0, Math.ceil(minutes / DAY))
  const ofDay = days * DAY - minutes
  return { days, hour: Math.floor(ofDay / 60), minute: ofDay % 60 }
}

export function describeReminder(minutes: number, allDay: boolean): string {
  if (allDay) {
    const { days, hour, minute } = splitAllDay(minutes)
    const time = `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
    if (days === 0) return t('reminders.sameDayAt', { time })
    if (days === 1) return t('reminders.dayBeforeAt', { time })
    if (days % 7 === 0) return t('reminders.weeksBeforeAt', { n: days / 7, time }, days / 7)
    return t('reminders.daysBeforeAt', { n: days, time }, days)
  }
  if (minutes === 0) return t('reminders.atStart')
  if (minutes % (7 * DAY) === 0) return t('reminders.weeksBefore', minutes / (7 * DAY))
  if (minutes % DAY === 0) return t('reminders.daysBefore', minutes / DAY)
  if (minutes % 60 === 0) return t('reminders.hoursBefore', minutes / 60)
  return t('reminders.minutesBefore', minutes)
}

/** Valid for the kind of event? */
export function isValidReminder(minutes: number, allDay: boolean): boolean {
  return Number.isInteger(minutes) && minutes <= MAX_BEFORE && minutes >= (allDay ? -(DAY - 1) : 0)
}

export const sortReminders = (list: number[]) => [...new Set(list)].sort((a, b) => a - b)
