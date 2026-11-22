export interface User {
  id: string
  email: string
  name: string
  created_at: string
}

export interface AuthResponse {
  token: string
  token_type: 'Bearer'
  expires_at: string
  user: User
}

export interface Calendar {
  id: string
  name: string
  color: string
  description: string
  visible: boolean
  created_at: string
  updated_at: string
}

export interface CalendarInput {
  name: string
  color?: string
  description?: string
  visible?: boolean
}

/** Termin (bei Serien: die Serie selbst) */
export interface CalEvent {
  id: string
  calendar_id: string
  uid: string
  title: string
  description: string
  location: string
  start: string
  end: string
  all_day: boolean
  timezone: string
  rrule: string | null
  exdates: string[]
  created_at: string
  updated_at: string
}

export interface EventInput {
  calendar_id: string
  title: string
  description: string
  location: string
  start: string
  end: string
  all_day: boolean
  timezone: string
  rrule: string | null
  exdates?: string[]
}

/** Einzelnes Vorkommen eines Termins in einem Zeitraum */
export interface Occurrence {
  id: string
  event_id: string
  calendar_id: string
  title: string
  description: string
  location: string
  start: string
  end: string
  all_day: boolean
  timezone: string
  recurring: boolean
  rrule: string | null
}

/** Vorkommen mit lokal berechneten Datumswerten für die Darstellung */
export interface ViewEvent extends Occurrence {
  startDate: Date
  /** exklusives Ende */
  endDate: Date
  color: string
}

export interface ServerInfo {
  name: string
  version: string
  api_version: number
  allow_registration: boolean
}

export interface ImportResult {
  imported: number
  skipped: string[]
}
