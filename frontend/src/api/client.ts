import type {
  AdminOverview, AdminSettings, AdminSettingsValues, AdminUser, AdminUserInput, AddressBook, AppPassword, AddressBookInput, AuthResponse, CalEvent, Calendar, CalendarInput, Contact, ContactImportResult,
  ContactInput, DbImportReport, EventInput, ImportResult, LogEntry, Occurrence, PushSubscriptionInput, ServerInfo, Task, TaskInput,
  TaskList, TaskListInput, TotpSetup, TotpStatus, User, ViewMode,
} from './types'
import { language, t } from '@/i18n'

const BASE = '/api'
const TOKEN_KEY = 'synchaven.token'

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

/** Requests whose 401 means a wrong password or code – not an ended session */
function checksCredentials(method: string, path: string) {
  return path.startsWith('/auth/login') || path.startsWith('/auth/totp/') ||
    (method === 'PUT' && path === '/auth/me') || (method === 'POST' && path === '/auth/app-passwords')
}

async function request<T>(method: string, path: string, opts: Options = {}): Promise<T> {
  let url = BASE + path
  if (opts.query) {
    const qs = new URLSearchParams()
    for (const [k, v] of Object.entries(opts.query)) if (v !== undefined && v !== '') qs.set(k, v)
    const s = qs.toString()
    if (s) url += '?' + s
  }
  const headers: Record<string, string> = { Accept: 'application/json', 'Accept-Language': language }
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
    throw new ApiError(0, 'network', t('errors.network'))
  }

  if (!res.ok) {
    let code = 'error'
    let message = t('errors.status', { status: res.status })
    try {
      const data = await res.json()
      code = data.error ?? code
      message = data.message ?? message
    } catch { /* no JSON */ }
    if (res.status === 401 && token && !checksCredentials(method, path)) onUnauthorized()
    throw new ApiError(res.status, code, message)
  }
  if (res.status === 204) return undefined as T
  return (opts.asText ? await res.text() : await res.json()) as T
}

export const api = {
  info: () => request<ServerInfo>('GET', '/info'),

  /** `code`: code of the authenticator app or a recovery code, for accounts with two-factor authentication */
  login: (email: string, password: string, code?: string) =>
    request<AuthResponse>('POST', '/auth/login', { body: { email, password, code } }),
  /** `language`: language of the initial data (calendar, task list, address book) */
  register: (email: string, password: string, name: string, language: 'de' | 'en') =>
    request<AuthResponse>('POST', '/auth/register', { body: { email, password, name, language } }),
  me: () => request<User>('GET', '/auth/me'),
  updateMe: (data: {
    name?: string; email?: string; current_password?: string; new_password?: string; default_view?: ViewMode
    language?: 'de' | 'en'
  }) =>
    request<User>('PUT', '/auth/me', { body: data }),

  totpStatus: () => request<TotpStatus>('GET', '/auth/totp'),
  totpSetup: (currentPassword: string) =>
    request<TotpSetup>('POST', '/auth/totp/setup', { body: { current_password: currentPassword } }),
  totpEnable: (code: string) => request<{ recovery_codes: string[] }>('POST', '/auth/totp/enable', { body: { code } }),
  totpDisable: (currentPassword: string, code: string) =>
    request<void>('POST', '/auth/totp/disable', { body: { current_password: currentPassword, code } }),
  totpRecoveryCodes: (currentPassword: string, code: string) =>
    request<{ recovery_codes: string[] }>('POST', '/auth/totp/recovery-codes', {
      body: { current_password: currentPassword, code },
    }),
  appPasswords: () => request<AppPassword[]>('GET', '/auth/app-passwords'),
  createAppPassword: (name: string, currentPassword: string) =>
    request<AppPassword>('POST', '/auth/app-passwords', { body: { name, current_password: currentPassword } }),
  deleteAppPassword: (id: string) => request<void>('DELETE', `/auth/app-passwords/${id}`),

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

  taskLists: () => request<TaskList[]>('GET', '/task-lists'),
  createTaskList: (data: TaskListInput) => request<TaskList>('POST', '/task-lists', { body: data }),
  updateTaskList: (id: string, data: TaskListInput) => request<TaskList>('PUT', `/task-lists/${id}`, { body: data }),
  deleteTaskList: (id: string) => request<void>('DELETE', `/task-lists/${id}`),
  clearCompletedTasks: (id: string) => request<{ deleted: number }>('POST', `/task-lists/${id}/clear-completed`),
  tasks: () => request<Task[]>('GET', '/tasks'),
  createTask: (data: TaskInput) => request<Task>('POST', '/tasks', { body: data }),
  updateTask: (id: string, data: TaskInput) => request<Task>('PUT', `/tasks/${id}`, { body: data }),
  setTaskCompleted: (id: string, completed: boolean) =>
    request<Task>('PUT', `/tasks/${id}/completed`, { body: { completed } }),
  deleteTask: (id: string) => request<void>('DELETE', `/tasks/${id}`),

  pushPublicKey: () => request<{ public_key: string }>('GET', '/push/public-key'),
  pushSubscribe: (data: PushSubscriptionInput) => request<void>('POST', '/push/subscriptions', { body: data }),
  pushUnsubscribe: (endpoint: string) => request<void>('DELETE', '/push/subscriptions', { body: { endpoint } }),
  pushTest: (endpoint?: string) => request<{ sent: number }>('POST', '/push/test', { body: { endpoint } }),

  addressBooks: () => request<AddressBook[]>('GET', '/addressbooks'),
  createAddressBook: (data: AddressBookInput) => request<AddressBook>('POST', '/addressbooks', { body: data }),
  updateAddressBook: (id: string, data: AddressBookInput) =>
    request<AddressBook>('PUT', `/addressbooks/${id}`, { body: data }),
  deleteAddressBook: (id: string) => request<void>('DELETE', `/addressbooks/${id}`),
  exportAddressBook: (id: string) => request<string>('GET', `/addressbooks/${id}/export`, { asText: true }),
  importAddressBook: (id: string, vcf: string) =>
    request<ContactImportResult>('POST', `/addressbooks/${id}/import`, { rawBody: vcf, contentType: 'text/vcard' }),

  contacts: (addressbookId?: string) =>
    request<Contact[]>('GET', '/contacts', { query: { addressbook_id: addressbookId } }),
  contact: (id: string) => request<Contact>('GET', `/contacts/${id}`),
  createContact: (data: ContactInput) => request<Contact>('POST', '/contacts', { body: data }),
  updateContact: (id: string, data: ContactInput) => request<Contact>('PUT', `/contacts/${id}`, { body: data }),
  deleteContact: (id: string) => request<void>('DELETE', `/contacts/${id}`),
  renameContactCategory: (name: string, newName: string) =>
    request<{ updated: number }>('POST', '/contact-categories/rename', { body: { name, new_name: newName } }),
  deleteContactCategory: (name: string) =>
    request<{ updated: number }>('POST', '/contact-categories/delete', { body: { name } }),

  adminOverview: () => request<AdminOverview>('GET', '/admin/overview'),
  adminSettings: () => request<AdminSettings>('GET', '/admin/settings'),
  updateAdminSettings: (data: Partial<AdminSettingsValues>) =>
    request<AdminSettings>('PUT', '/admin/settings', { body: data }),
  adminUsers: () => request<AdminUser[]>('GET', '/admin/users'),
  /** `language`: language of the new account's initial data */
  createAdminUser: (data: { email: string; name: string; password: string; is_admin: boolean; language: 'de' | 'en' }) =>
    request<AdminUser>('POST', '/admin/users', { body: data }),
  updateAdminUser: (id: string, data: AdminUserInput) => request<AdminUser>('PUT', `/admin/users/${id}`, { body: data }),
  logoutAdminUser: (id: string) => request<AdminUser>('POST', `/admin/users/${id}/logout`),
  deleteAdminUser: (id: string) => request<void>('DELETE', `/admin/users/${id}`),

  adminLogs: (after = 0) =>
    request<{ entries: LogEntry[]; started_at: string; capacity: number }>('GET', '/admin/logs', { query: { after: String(after) } }),
  clearAdminLogs: () => request<void>('DELETE', '/admin/logs'),

  adminExport: () => request<string>('GET', '/admin/export', { asText: true }),
  adminImport: (json: string) =>
    request<DbImportReport>('POST', '/admin/import', {
      rawBody: json, contentType: 'application/json', query: { replace: 'true' },
    }),
}
