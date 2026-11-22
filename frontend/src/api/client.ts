import type {
  AuthResponse, CalEvent, Calendar, CalendarInput, EventInput, ImportResult, Occurrence, ServerInfo, User,
} from './types'

const BASE = '/api'
const TOKEN_KEY = 'kalender.token'

export class ApiError extends Error {
  constructor(public status: number, public code: string, message: string) {
    super(message)
  }
}

let token: string | null = localStorage.getItem(TOKEN_KEY)
let onUnauthorized: () => void = () => {}

export function setToken(t: string | null) {
  token = t
  if (t) localStorage.setItem(TOKEN_KEY, t)
  else localStorage.removeItem(TOKEN_KEY)
}
export const getToken = () => token
export function setUnauthorizedHandler(fn: () => void) {
  onUnauthorized = fn
}

interface Options {
  body?: unknown
  rawBody?: string
  contentType?: string
  query?: Record<string, string | undefined>
  asText?: boolean
}

async function request<T>(method: string, path: string, opts: Options = {}): Promise<T> {
  let url = BASE + path
  if (opts.query) {
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(opts.query)) if (v !== undefined && v !== '') qs.set(k, v)
    const s = qs.toString()
    if (s) url += '?' + s
  }
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (token) headers.Authorization = `Bearer ${token}`
  let body: BodyInit | undefined
  if (opts.rawBody !== undefined) {
    body = opts.rawBody
    headers['Content-Type'] = opts.contentType ?? 'text/plain'
  } else if (opts.body !== undefined) {
    body = JSON.stringify(opts.body)
    headers['Content-Type'] = 'application/json'
  }

  let res: Response
  try {
    res = await fetch(url, { method, headers, body })
  } catch {
    throw new ApiError(0, 'network', 'Server nicht erreichbar')
  }

  if (!res.ok) {
    let code = 'error'
    let message = `Fehler ${res.status}`
    try {
      const data = await res.json()
      code = data.error ?? code
      message = data.message ?? message
    } catch { /* kein JSON */ }
    if (res.status === 401 && token && !path.startsWith('/auth/login')) onUnauthorized()
    throw new ApiError(res.status, code, message)
  }
  if (res.status === 204) return undefined as T
  return (opts.asText ? await res.text() : await res.json()) as T
}

export const api = {
  info: () => request<ServerInfo>('GET', '/info'),

  login: (email: string, password: string) => request<AuthResponse>('POST', '/auth/login', { body: { email, password } }),
  register: (email: string, password: string, name: string) =>
    request<AuthResponse>('POST', '/auth/register', { body: { email, password, name } }),
  me: () => request<User>('GET', '/auth/me'),
  updateMe: (data: { name?: string; email?: string; current_password?: string; new_password?: string }) =>
    request<User>('PUT', '/auth/me', { body: data }),

  calendars: () => request<Calendar[]>('GET', '/calendars'),
  createCalendar: (data: CalendarInput) => request<Calendar>('POST', '/calendars', { body: data }),
  updateCalendar: (id: string, data: CalendarInput) => request<Calendar>('PUT', `/calendars/${id}`, { body: data }),
  deleteCalendar: (id: string) => request<void>('DELETE', `/calendars/${id}`),
  exportCalendar: (id: string) => request<string>('GET', `/calendars/${id}/export`, { asText: true }),
  importCalendar: (id: string, ics: string, timezone: string) =>
    request<ImportResult>('POST', `/calendars/${id}/import`, {
      rawBody: ics, contentType: 'text/calendar', query: { timezone },
    }),

  occurrences: (start: Date, end: Date, q?: string) =>
    request<Occurrence[]>('GET', '/events', { query: { start: start.toISOString(), end: end.toISOString(), q } }),
  event: (id: string) => request<CalEvent>('GET', `/events/${id}`),
  createEvent: (data: EventInput) => request<CalEvent>('POST', '/events', { body: data }),
  updateEvent: (id: string, data: EventInput) => request<CalEvent>('PUT', `/events/${id}`, { body: data }),
  deleteEvent: (id: string) => request<void>('DELETE', `/events/${id}`),
  addExdate: (id: string, occurrenceStart: string) =>
    request<CalEvent>('POST', `/events/${id}/exdates`, { body: { occurrence_start: occurrenceStart } }),
}
