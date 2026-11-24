export type ViewMode = 'month' | 'week' | 'day' | 'list'

export interface User {
  id: string
  email: string
  name: string
  /** Standardansicht des Kalenders */
  default_view: ViewMode
  created_at: string
  /** Darf die ganze Datenbank exportieren und importieren (das erste registrierte Konto) */
  is_admin: boolean
}

/** Ergebnis von POST /api/admin/import */
export interface DbImportReport {
  /** Übernommene Einträge je Tabelle, z. B. "events: 12" */
  summary: string[]
  /** Übersprungene Tabellen und Spalten */
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
  /** `birthdays`: automatisch aus den Kontakten erzeugt, Termine schreibgeschützt */
  kind: 'default' | 'birthdays'
  /** Standard-Erinnerungen in Minuten vor Beginn (Termine mit Uhrzeit) */
  default_reminders: number[]
  /** Standard-Erinnerungen für ganztägige Termine (negativ = Uhrzeit am Tag selbst) */
  default_all_day_reminders: number[]
  created_at: string
  updated_at: string
}

export interface CalendarInput {
  name: string
  color?: string
  description?: string
  visible?: boolean
  /** Fehlt das Feld, bleiben die bisherigen Standard-Erinnerungen erhalten */
  default_reminders?: number[]
  default_all_day_reminders?: number[]
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
  /** Erinnerungen in Minuten vor Beginn; `null` = Standard des Kalenders */
  reminders: number[] | null
  /** Kontakt, aus dessen Geburtstag der Termin erzeugt wurde */
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
  /** `null` = Standard des Kalenders; fehlt das Feld, bleiben die bisherigen Erinnerungen erhalten */
  reminders?: number[] | null
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
  contact_id: string | null
  /** Automatisch erzeugte Termine (Geburtstage) können nicht bearbeitet werden */
  read_only: boolean
  /** Erinnerungen des Termins; `null` = Standard des Kalenders */
  reminders: number[] | null
}

/** Vorkommen mit lokal berechneten Datumswerten für die Darstellung */
export interface ViewEvent extends Occurrence {
  startDate: Date
  /** exklusives Ende */
  endDate: Date
  color: string
  /** Gesetzt, wenn der Eintrag eine Aufgabe ist (`calendar_id` ist dann die Aufgabenliste) */
  task?: Task
}

// ---------- Aufgaben ----------

export interface TaskList {
  id: string
  name: string
  color: string
  /** Aufgaben dieser Liste im Kalender anzeigen */
  visible: boolean
  /** Anzahl offener Aufgaben */
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
  title: string
  notes: string
  /** Fälligkeit; ganztägig als Mitternacht UTC, `null` = ohne Datum */
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
  /** Fehlt das Feld, bleibt der Status erhalten */
  completed?: boolean
}

export interface ServerInfo {
  name: string
  version: string
  api_version: number
  allow_registration: boolean
}

/** Abonnement eines Browsers, wie es `PushSubscription.toJSON()` liefert, plus Zeitzone */
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

// ---------- Kontakte ----------

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

/** Telefonnummer, E-Mail-Adresse oder Website mit Typ (`mobile`, `home`, `work`, … oder eigene Bezeichnung) */
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
  /** `YYYY-MM-DD` oder `--MM-DD` (ohne Jahr) */
  birthday: string
  note: string
  /** Kategorien (vCard `CATEGORIES`) */
  categories: string[]
}

export interface Contact extends ContactData {
  id: string
  addressbook_id: string
  uid: string
  has_photo: boolean
  /** nur bei Einzelabfrage: data:-URL */
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
