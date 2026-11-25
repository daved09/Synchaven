// Translations of the web UI: one file per language in src/locales/ (de.json, en.json).
//
// The language follows the browser (English if it prefers none of the supported ones), unless one is
// chosen on registration or in the settings. It is fixed for the
// lifetime of the page – changing it reloads the page. API requests send it as Accept-Language,
// so the server's messages are in the same language.

import { createI18n } from 'vue-i18n'
import de from './locales/de.json'
import en from './locales/en.json'
import { loadSettings } from '@/stores/settings'

export const LANGUAGES = ['de', 'en'] as const
export type Language = (typeof LANGUAGES)[number]

const primary = (tag: string) => tag.toLowerCase().split(/[-_]/)[0]
const isLanguage = (s: string): s is Language => (LANGUAGES as readonly string[]).includes(s)

/** The browser's preferred supported language */
export function browserLanguage(): Language {
  for (const tag of navigator.languages ?? [navigator.language]) {
    const p = primary(tag)
    if (isLanguage(p)) return p
  }
  return 'en'
}

/** Language of the UI: chosen in the settings, otherwise the browser's */
export const language: Language = (() => {
  const chosen = loadSettings().language
  return chosen === 'auto' ? browserLanguage() : chosen
})()

/** Choices for language selects – each language in its own name */
export const LANGUAGE_OPTIONS: { title: string; value: Language }[] = [
  { title: 'Deutsch', value: 'de' },
  { title: 'English', value: 'en' },
]

/** Locale for date and number formats: the browser's variant of the UI language (en-GB, de-AT …) */
export const LOCALE: string =
  (navigator.languages ?? []).find((l) => primary(l) === language) ?? (language === 'de' ? 'de-DE' : 'en-US')

document.documentElement.lang = language

export const i18n = createI18n({
  legacy: false,
  locale: language,
  fallbackLocale: 'en',
  messages: { de, en },
})

/** Translation outside of templates (in templates: `$t`) */
export const t = i18n.global.t
