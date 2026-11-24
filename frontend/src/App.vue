<script setup lang="ts">
import { onBeforeUnmount, ref, watchEffect } from 'vue'
import { useTheme } from 'vuetify'
import { buildM3Colors } from './plugins/theme'
import { useNotifyStore } from './stores/notify'
import { useSettingsStore } from './stores/settings'

const theme = useTheme()
const { settings } = useSettingsStore()
const notify = useNotifyStore()

// Watch the system setting for light/dark
const media = window.matchMedia('(prefers-color-scheme: dark)')
const systemDark = ref(media.matches)
const onMedia = (e: MediaQueryListEvent) => (systemDark.value = e.matches)
media.addEventListener('change', onMedia)
onBeforeUnmount(() => media.removeEventListener('change', onMedia))

watchEffect(() => {
  theme.themes.value.light.colors = { ...theme.themes.value.light.colors, ...buildM3Colors(settings.seedColor, false) }
  theme.themes.value.dark.colors = { ...theme.themes.value.dark.colors, ...buildM3Colors(settings.seedColor, true) }
  const dark = settings.themeMode === 'dark' || (settings.themeMode === 'system' && systemDark.value)
  theme.change(dark ? 'dark' : 'light')
  const meta = document.querySelector('meta[name="theme-color"]')
  meta?.setAttribute('content', (dark ? theme.themes.value.dark : theme.themes.value.light).colors.surface as string)
})
</script>

<template>
  <v-app>
    <router-view />
    <v-snackbar v-model="notify.show" :color="notify.error ? 'error' : undefined" timeout="4000" location="bottom">
      {{ notify.text }}
      <template #actions>
        <v-btn variant="text" @click="notify.show = false">OK</v-btn>
      </template>
    </v-snackbar>
  </v-app>
</template>
