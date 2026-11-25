export type ViewMode = 'month' | 'week' | 'day' | 'list'

export interface User {
  id: string
  email: string
  name: string
  /** The calendar's default view */
  default_view: ViewMode
  /** Language of the texts the server stores for the account (default names, birthday events) */
  language: 'de' | 'en'
  created_at: string
  /** May open the admin page (initially the first registered account) */
  is_admin: boolean
}

/** Account in the admin page's user list */
export interface AdminUser {
  id: string
  email: string
  name: string
  is_admin: boolean
  /** Disabled: no sign-in, no sync */
  disabled: boolean
  created_at: string
  last_login_at: string | null
  calendars: number
  events: number
  contacts: number
  tasks: number
  /** Browsers with notifications enabled */
  devices: number
}

export interface AdminUserInput {
  name?: string
  email?: string
  password?: string
  is_admin?: boolean
  disabled?: boolean
}

export interface AdminSettingsValues {
  allow_registration: boolean
  token_ttl_days: number
}

/** Admin settings; `defaults` come from the environment variables */
export interface AdminSettings extends AdminSettingsValues {
  defaults: AdminSettingsValues
}

export interface AdminOverview {
  version: string
  started_at: string
  /** Database size in bytes */
  database_size: number
  counts: {
    users: number
    disabled_users: number
    calendars: number
    events: number
    addressbooks: number
    contacts: number
    tasks: number
    push_subscriptions: number
  }
  config: { bind: string; data_dir: string; cors_origins: string[]; vapid_subject: string }
}

export type LogLevel = 'ERROR' | 'WARN' | 'INFO' | 'DEBUG' | 'TRACE'

/** Entry from GET /api/admin/logs */
export interface LogEntry {
  /** Increasing number, for fetching only newer entries */
  id: number
  time: string
  level: LogLevel
  /** Module, e.g. "synchaven::routes::admin" */
  target: string
  message: string
}

/** Result of POST /api/admin/import */
export interface DbImportReport {
  /** Imported entries per table, e.g. "events: 12" */
  summary: string[]
  /** Skipped tables and columns */
  warnings: string[]
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
  /** `birthdays`: generated automatically from the contacts, events read-only */
  kind: 'default' | 'birthdays'
  /** Default reminders in minutes before the start (timed events) */
  default_reminders: number[]
  /** Default reminders for all-day events (negative = time on the day itself) */
  default_all_day_reminders: number[]
  created_at: string
  updated_at: string
}

export interface CalendarInput {
  name: string
  color?: string
  description?: string
  visible?: boolean
  /** If the field is missing, the existing default reminders are kept */
  default_reminders?: number[]
  default_all_day_reminders?: number[]
}

/** Event (for series: the series itself) */
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
  /** Reminders in minutes before the start; `null` = calendar default */
  reminders: number[] | null
  /** Contact whose birthday the event was generated from */
  contact_id: string | null
  read_only: boolean
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
  /** `null` = calendar default; if the field is missing, the existing reminders are kept */
  reminders?: number[] | null
}

/** Single occurrence of an event in a range */
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
  contact_id: string | null
  /** Automatically generated events (birthdays) cannot be edited */
  read_only: boolean
  /** The event's reminders; `null` = calendar default */
  reminders: number[] | null
}

/** Occurrence with locally computed date values for display */
export interface ViewEvent extends Occurrence {
  startDate: Date
  /** exclusive end */
  endDate: Date
  color: string
  /** Set if the entry is a task (`calendar_id` is then the task list) */
  task?: Task
}

// ---------- Tasks ----------

export interface TaskList {
  id: string
  name: string
  color: string
  /** Show this list's tasks in the calendar */
  visible: boolean
  /** Number of open tasks */
  open_count: number
  created_at: string
  updated_at: string
}

export interface TaskListInput {
  name: string
  color?: string
  visible?: boolean
}

export interface Task {
  id: string
  list_id: string
  /** Parent task if this is a subtask */
  parent_id: string | null
  title: string
  notes: string
  /** Due date; all-day as midnight UTC, `null` = without date */
  due: string | null
  all_day: boolean
  completed: boolean
  completed_at: string | null
  created_at: string
  updated_at: string
}

export interface TaskInput {
  list_id: string
  title: string
  notes: string
  due: string | null
  all_day: boolean
  /** If the field is missing, the status is kept */
  completed?: boolean
  /** Parent task; `null` = top-level task; if the field is missing, the assignment is kept */
  parent_id?: string | null
}

export interface ServerInfo {
  name: string
  version: string
  api_version: number
  allow_registration: boolean
}

/** A browser's subscription as returned by `PushSubscription.toJSON()`, plus time zone */
export interface PushSubscriptionInput {
  endpoint: string
  keys: { p256dh: string; auth: string }
  timezone: string
  user_agent?: string
}

export interface ImportResult {
  imported: number
  skipped: string[]
}

// ---------- Contacts ----------

export interface AddressBook {
  id: string
  name: string
  description: string
  contact_count: number
  created_at: string
  updated_at: string
}

export interface AddressBookInput {
  name: string
  description?: string
}

/** Phone number, email address or website with a type (`mobile`, `home`, `work`, … or a custom label) */
export interface TypedValue {
  type: string
  value: string
}

export interface PostalAddress {
  type: string
  po_box: string
  extended: string
  street: string
  locality: string
  region: string
  postal_code: string
  country: string
}

export interface ContactData {
  full_name: string
  prefix: string
  given_name: string
  additional_names: string
  family_name: string
  suffix: string
  nickname: string
  organization: string
  department: string
  title: string
  phones: TypedValue[]
  emails: TypedValue[]
  addresses: PostalAddress[]
  urls: TypedValue[]
  /** `YYYY-MM-DD` or `--MM-DD` (without year) */
  birthday: string
  note: string
  /** Categories (vCard `CATEGORIES`) */
  categories: string[]
}

export interface Contact extends ContactData {
  id: string
  addressbook_id: string
  uid: string
  has_photo: boolean
  /** only for single queries: data: URL */
  photo?: string
  created_at: string
  updated_at: string
}

export interface ContactInput extends ContactData {
  addressbook_id: string
}

export interface ContactImportResult {
  imported: number
  created: number
  updated: number
  unchanged: number
  skipped: string[]
}
