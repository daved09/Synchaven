import type { Contact, ContactData, PostalAddress, TypedValue } from '@/api/types'

export interface TypeOption {
  title: string
  value: string
}

export const PHONE_TYPES: TypeOption[] = [
  { title: 'Mobil', value: 'mobile' },
  { title: 'Privat', value: 'home' },
  { title: 'Geschäftlich', value: 'work' },
  { title: 'Fax', value: 'fax' },
  { title: 'Pager', value: 'pager' },
  { title: 'Sonstige', value: 'other' },
]

export const OTHER_TYPES: TypeOption[] = [
  { title: 'Privat', value: 'home' },
  { title: 'Geschäftlich', value: 'work' },
  { title: 'Sonstige', value: 'other' },
]

/** Display text of a type; custom labels are shown unchanged. */
export function typeLabel(type: string, phone = false): string {
  const known = (phone ? PHONE_TYPES : OTHER_TYPES).find((t) => t.value === type)
  return known?.title ?? (type || 'Sonstige')
}

export function emptyContact(): ContactData {
  return {
    full_name: '', prefix: '', given_name: '', additional_names: '', family_name: '', suffix: '', nickname: '',
    organization: '', department: '', title: '', phones: [], emails: [], addresses: [], urls: [], birthday: '', note: '',
    categories: [],
  }
}

export function emptyAddress(type = 'home'): PostalAddress {
  return { type, po_box: '', extended: '', street: '', locality: '', region: '', postal_code: '', country: '' }
}

/** Name from the name parts (as in the backend). */
export function partsName(c: ContactData): string {
  return [c.prefix, c.given_name, c.additional_names, c.family_name, c.suffix]
    .map((s) => s.trim())
    .filter(Boolean)
    .join(' ')
}

/** Own name: the nickname, if set, otherwise the name from the name parts (as in the backend). */
export function ownName(c: ContactData): string {
  return c.nickname.trim() || partsName(c)
}

/** Display name from nickname, name parts, company, email or phone (as in the backend). */
export function derivedName(c: ContactData): string {
  const name = ownName(c)
  return [name, c.organization.trim(), ...c.emails.map((e) => e.value.trim()), ...c.phones.map((p) => p.value.trim())]
    .find(Boolean) ?? ''
}

export function displayName(c: ContactData): string {
  return c.full_name || derivedName(c) || '(Ohne Namen)'
}

/**
 * Actual name without nickname (for the header of the detail view, below which the nickname is shown separately):
 * name parts, otherwise company, email or phone, otherwise the display name
 */
export function realName(c: ContactData): string {
  return partsName(c) || derivedName({ ...c, nickname: '' }) || displayName(c)
}

export function initials(c: ContactData): string {
  const parts = c.given_name || c.family_name
    ? [c.given_name, c.family_name]
    : displayName(c).split(/\s+/)
  const letters = parts.filter(Boolean).slice(0, 2).map((p) => [...p][0]?.toUpperCase() ?? '')
  return letters.join('') || '?'
}

const AVATAR_COLORS = ['#6750A4', '#0B57D0', '#00897B', '#33B679', '#E37400', '#F4511E', '#D50000', '#8E24AA', '#3F51B5', '#795548']

export function avatarColor(c: Contact): string {
  let h = 0
  for (const ch of c.uid || c.id) h = (h * 31 + ch.charCodeAt(0)) >>> 0
  return AVATAR_COLORS[h % AVATAR_COLORS.length]
}

/** Letter for grouping the list (by the actual name, not the nickname) */
export function groupLetter(c: ContactData): string {
  const first = realName(c).trim()[0]?.toUpperCase() ?? '#'
  const base = first.normalize('NFD').replace(/[̀-ͯ]/g, '')
  return /[A-Z]/.test(base) ? base : '#'
}

export function subtitle(c: Contact): string {
  const org = [c.title, c.organization].filter(Boolean).join(', ')
  return org || c.emails[0]?.value || c.phones[0]?.value || ''
}

export function matches(c: Contact, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  const digits = q.replace(/[^\d+]/g, '')
  const hay = [
    c.full_name, c.nickname, c.organization, c.department, c.title, c.note,
    ...c.emails.map((e) => e.value), ...c.phones.map((p) => p.value),
    ...c.addresses.map((a) => `${a.street} ${a.postal_code} ${a.locality} ${a.country}`), ...c.categories,
  ].join(' ').toLowerCase()
  if (hay.includes(q)) return true
  return digits.length >= 3 && c.phones.some((p) => p.value.replace(/[^\d+]/g, '').includes(digits))
}

/** Same category (case-insensitive, as in the backend) */
export function sameCategory(a: string, b: string): boolean {
  return a.toLowerCase() === b.toLowerCase()
}

export function formatAddress(a: PostalAddress): string[] {
  return [
    a.po_box && `Postfach ${a.po_box}`,
    a.extended,
    a.street,
    [a.postal_code, a.locality].filter(Boolean).join(' '),
    a.region,
    a.country,
  ].filter((l): l is string => !!l && !!l.trim())
}

export function mapsUrl(a: PostalAddress): string {
  const q = [a.street, a.postal_code, a.locality, a.country].filter(Boolean).join(', ')
  return `https://www.openstreetmap.org/search?query=${encodeURIComponent(q)}`
}

export function websiteUrl(t: TypedValue): string {
  return /^[a-z][a-z0-9+.-]*:/i.test(t.value) ? t.value : `https://${t.value}`
}

// ---------- Birthday ----------

/** `1990-05-12` → `12.05.1990`, `--05-12` → `12.05.` */
export function birthdayToInput(b: string): string {
  let m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(b)
  if (m) return `${m[3]}.${m[2]}.${m[1]}`
  m = /^--(\d{2})-(\d{2})$/.exec(b)
  if (m) return `${m[2]}.${m[1]}.`
  return b
}

/** `12.5.1990` / `12.05.` / `1990-05-12` → stored format; `null` for invalid input */
export function birthdayFromInput(s: string): string | null {
  const t = s.trim()
  if (!t) return ''
  let day: number, month: number, year: number | null
  let m = /^(\d{1,2})\.\s*(\d{1,2})\.?\s*(\d{4})?$/.exec(t)
  if (m) {
    day = +m[1]
    month = +m[2]
    year = m[3] ? +m[3] : null
  } else if ((m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(t))) {
    year = +m[1]
    month = +m[2]
    day = +m[3]
  } else {
    return null
  }
  const check = new Date(year ?? 2000, month - 1, day)
  if (check.getMonth() !== month - 1 || check.getDate() !== day) return null
  const mm = String(month).padStart(2, '0')
  const dd = String(day).padStart(2, '0')
  return year === null ? `--${mm}-${dd}` : `${year}-${mm}-${dd}`
}

export function formatBirthday(b: string): string {
  let m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(b)
  if (m) {
    const d = new Date(+m[1], +m[2] - 1, +m[3])
    const now = new Date()
    let age = now.getFullYear() - d.getFullYear()
    if (now.getMonth() < d.getMonth() || (now.getMonth() === d.getMonth() && now.getDate() < d.getDate())) age--
    const text = d.toLocaleDateString('de-DE', { day: 'numeric', month: 'long', year: 'numeric' })
    return age >= 0 && age < 130 ? `${text} (${age} Jahre)` : text
  }
  m = /^--(\d{2})-(\d{2})$/.exec(b)
  if (m) return new Date(2000, +m[1] - 1, +m[2]).toLocaleDateString('de-DE', { day: 'numeric', month: 'long' })
  return b
}
