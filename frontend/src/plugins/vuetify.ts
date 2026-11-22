import { createVuetify } from 'vuetify'
import * as components from 'vuetify/components'
import * as directives from 'vuetify/directives'
import { md3 } from 'vuetify/blueprints'
import { de } from 'vuetify/locale'
import 'vuetify/styles'
import { buildM3Colors } from './theme'
import { loadSettings } from '@/stores/settings'

const settings = loadSettings()

export default createVuetify({
  blueprint: md3,
  components,
  directives,
  locale: { locale: 'de', fallback: 'de', messages: { de } },
  date: { locale: { de: 'de-DE' } },
  theme: {
    defaultTheme: 'light',
    themes: {
      light: { dark: false, colors: buildM3Colors(settings.seedColor, false) },
      dark: { dark: true, colors: buildM3Colors(settings.seedColor, true) },
    },
  },
  defaults: {
    VTextField: { variant: 'outlined', density: 'comfortable' },
    VTextarea: { variant: 'outlined', density: 'comfortable' },
    VSelect: { variant: 'outlined', density: 'comfortable' },
    VDialog: { scrollable: true },
    VCard: { rounded: 'xl' },
  },
})
