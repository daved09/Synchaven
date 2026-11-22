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
