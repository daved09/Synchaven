// Generates a complete Material 3 color scheme from a base color
// (dynamic colors using Material You's "Tonal Spot" method).
import {
  argbFromHex,
  DynamicColor,
  hexFromArgb,
  Hct,
  MaterialDynamicColors as M,
  SchemeTonalSpot,
} from '@material/material-color-utilities'

const roles: Record<string, DynamicColor> = {
  primary: M.primary,
  'on-primary': M.onPrimary,
  'primary-container': M.primaryContainer,
  'on-primary-container': M.onPrimaryContainer,
  secondary: M.secondary,
  'on-secondary': M.onSecondary,
  'secondary-container': M.secondaryContainer,
  'on-secondary-container': M.onSecondaryContainer,
  tertiary: M.tertiary,
  'on-tertiary': M.onTertiary,
  'tertiary-container': M.tertiaryContainer,
  'on-tertiary-container': M.onTertiaryContainer,
  error: M.error,
  'on-error': M.onError,
  'error-container': M.errorContainer,
  'on-error-container': M.onErrorContainer,
  background: M.background,
  'on-background': M.onBackground,
  surface: M.surface,
  'on-surface': M.onSurface,
  'surface-bright': M.surfaceBright,
  'surface-dim': M.surfaceDim,
  'surface-container-lowest': M.surfaceContainerLowest,
  'surface-container-low': M.surfaceContainerLow,
  'surface-container': M.surfaceContainer,
  'surface-container-high': M.surfaceContainerHigh,
  'surface-container-highest': M.surfaceContainerHighest,
  'm3-surface-variant': M.surfaceVariant,
  'on-m3-surface-variant': M.onSurfaceVariant,
  outline: M.outline,
  'outline-variant': M.outlineVariant,
  'inverse-surface': M.inverseSurface,
  'on-inverse-surface': M.inverseOnSurface,
  'inverse-primary': M.inversePrimary,
}

export function buildM3Colors(seed: string, dark: boolean): Record<string, string> {
  const scheme = new SchemeTonalSpot(Hct.fromInt(argbFromHex(seed)), dark, 0)
  const colors: Record<string, string> = {}
  for (const [name, role] of Object.entries(roles)) {
    colors[name] = hexFromArgb(role.getArgb(scheme))
  }
  // Vuetify uses "surface-variant" for tooltips/snackbars – in M3 that is inverse-surface.
  colors['surface-variant'] = colors['inverse-surface']
  colors['on-surface-variant'] = colors['on-inverse-surface']
  colors['surface-light'] = colors['surface-container-high']
  colors['primary-darken-1'] = colors['on-primary-container']
  colors['secondary-darken-1'] = colors['on-secondary-container']
  colors.info = colors.tertiary
  colors.success = dark ? '#7DDA8B' : '#1E6B32'
  colors.warning = dark ? '#F3C15F' : '#7A5900'
  return colors
}

/** Suggested colors for the base color and calendars */
export const SEED_COLORS = [
  '#6750A4', '#0B57D0', '#006A6A', '#386A20', '#7D5260', '#B3261E',
  '#8B5000', '#5B5F97', '#00639B', '#984061', '#6D5E0F', '#4A6267',
]

export const CALENDAR_COLORS = [
  '#6750A4', '#0B57D0', '#039BE5', '#00897B', '#33B679', '#7CB342',
  '#F6BF26', '#F4511E', '#D50000', '#E67C73', '#8E24AA', '#616161',
]

/** Readable text color on any background color */
export function onColor(hex: string): string {
  const h = hex.replace('#', '')
  const [r, g, b] = [0, 2, 4].map((i) => parseInt(h.slice(i, i + 2), 16) / 255)
  const lin = (c: number) => (c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4)
  const lum = 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
  return lum > 0.4 ? '#1D1B20' : '#FFFFFF'
}
