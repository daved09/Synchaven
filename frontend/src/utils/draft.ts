// Vorläufige Terminkarte, die während des Anlegens im Kalender zu sehen ist.
import type { ViewEvent } from '@/api/types'
import { startOfDay } from './date'

export const DRAFT_ID = '__draft__'
export const DRAFT_TITLE = '(Kein Titel)'

export interface EventDraft {
  start: Date
  /** exklusives Ende */
  end: Date
  allDay: boolean
  title: string
  calendarId: string
}

export function isDraft(ev: ViewEvent): boolean {
  return ev.id === DRAFT_ID
}

export function draftEvent(d: EventDraft, color: string): ViewEvent {
  const startDate = d.allDay ? startOfDay(d.start) : d.start
  const endDate = d.allDay ? startOfDay(d.end) : d.end
  return {
    id: DRAFT_ID,
    event_id: DRAFT_ID,
    calendar_id: d.calendarId,
    title: d.title.trim() || DRAFT_TITLE,
    description: '',
    location: '',
    start: startDate.toISOString(),
    end: endDate.toISOString(),
    all_day: d.allDay,
    timezone: '',
    recurring: false,
    rrule: null,
    contact_id: null,
    // Nicht ziehbar: Zeiten werden im Dialog geändert
    read_only: true,
    startDate,
    endDate,
    color,
  }
}
