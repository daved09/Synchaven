<script setup lang="ts">
import { LANGUAGE_OPTIONS, browserLanguage, t } from '@/i18n'
import { computed, ref, watch } from 'vue'
import { SEED_COLORS } from '@/plugins/theme'
import type { ViewMode } from '@/api/types'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'
import { useSettingsStore, type Settings } from '@/stores/settings'
import { disablePush, enablePush, pushState, testPush, type PushState } from '@/utils/push'

const open = defineModel<boolean>({ required: true })
const { settings } = useSettingsStore()
const auth = useAuthStore()
const notify = useNotifyStore()

/** The default view belongs to the user account */
function setDefaultView(v: ViewMode) {
  auth.update({ default_view: v }).catch((e) => notify.fail(e))
}

// ---------- Notifications ----------
const push = ref<PushState | null>(null)
const pushBusy = ref(false)
watch(open, (v) => v && pushState().then((s) => (push.value = s)), { immediate: true })

const pushHint = computed(() => t(`settings.pushHint.${push.value ?? 'off'}`))

async function togglePush(on: boolean | null) {
  pushBusy.value = true
  try {
    if (on) {
      push.value = await enablePush()
      if (push.value !== 'on') notify.fail(new Error(pushHint.value))
    } else {
      await disablePush()
      push.value = await pushState()
    }
  } catch (e) {
    notify.fail(e)
    push.value = await pushState()
  } finally {
    pushBusy.value = false
  }
}

async function sendTest() {
  pushBusy.value = true
  try {
    await testPush()
    notify.info(t('settings.testSent'))
  } catch (e) {
    notify.fail(e)
  } finally {
    pushBusy.value = false
  }
}

const weekStarts = [
  { title: t('settings.weekdays.monday'), value: 1 },
  { title: t('settings.weekdays.sunday'), value: 0 },
  { title: t('settings.weekdays.saturday'), value: 6 },
]
const views = [
  { title: t('views.month'), value: 'month' },
  { title: t('views.week'), value: 'week' },
  { title: t('views.day'), value: 'day' },
  { title: t('views.list'), value: 'list' },
]

// ---------- Language ----------
// Fixed for the lifetime of the page (see i18n.ts), so a change reloads it.
const languages = [{ title: t('settings.languageAuto'), value: 'auto' }, ...LANGUAGE_OPTIONS]
async function setLanguage(v: Settings['language']) {
  settings.language = v
  // The account's language decides the texts the server stores (e.g. birthday events)
  if (auth.user) {
    try {
      await auth.update({ language: v === 'auto' ? browserLanguage() : v })
    } catch (e) {
      notify.fail(e)
    }
  }
  // The settings store writes to localStorage in a watcher – it has run by now
  location.reload()
}
</script>

<template>
  <v-dialog v-model="open" max-width="520">
    <v-card :title="$t('settings.title')" color="surface-container-high">
      <v-card-text>
        <div class="text-title-small mb-2">{{ $t('settings.appearance') }}</div>
        <v-btn-toggle v-model="settings.themeMode" mandatory divided variant="outlined" rounded="xl" density="comfortable" class="mb-4">
          <v-btn value="system" prepend-icon="mdi-theme-light-dark">{{ $t('settings.themeSystem') }}</v-btn>
          <v-btn value="light" prepend-icon="mdi-white-balance-sunny">{{ $t('settings.themeLight') }}</v-btn>
          <v-btn value="dark" prepend-icon="mdi-weather-night">{{ $t('settings.themeDark') }}</v-btn>
        </v-btn-toggle>

        <div class="text-title-small mb-2">{{ $t('settings.seedColor') }}</div>
        <div class="d-flex flex-wrap ga-2 mb-6 align-center">
          <button
            v-for="c in SEED_COLORS" :key="c" class="swatch" :style="{ background: c }" :aria-label="$t('settings.seedColorNamed', { color: c })"
            @click="settings.seedColor = c"
          >
            <v-icon v-if="settings.seedColor.toUpperCase() === c" icon="mdi-check" color="white" size="20" />
          </button>
          <v-menu :close-on-content-click="false">
            <template #activator="{ props: p }">
              <v-btn v-bind="p" icon size="36" variant="tonal" :title="$t('settings.customColor')"><v-icon icon="mdi-palette-outline" /></v-btn>
            </template>
            <v-color-picker v-model="settings.seedColor" mode="hex" :modes="['hex']" />
          </v-menu>
        </div>

        <v-select
          :model-value="settings.language" :items="languages" :label="$t('settings.language')"
          prepend-inner-icon="mdi-translate" class="mb-2" @update:model-value="setLanguage"
        />

        <div class="text-title-small mb-2">{{ $t('settings.calendar') }}</div>
        <v-select
          :model-value="auth.user?.default_view ?? settings.defaultView" :items="views" :label="$t('settings.defaultView')"
          @update:model-value="setDefaultView"
        />
        <v-select v-model="settings.weekStartsOn" :items="weekStarts" :label="$t('settings.weekStartsOn')" />
        <v-switch v-model="settings.showWeekNumbers" :label="$t('settings.showWeekNumbers')" color="primary" inset hide-details />

        <div class="text-title-small mt-4 mb-1">{{ $t('settings.notifications') }}</div>
        <v-switch
          :model-value="push === 'on'" :label="$t('settings.pushToggle')" color="primary" inset hide-details
          :disabled="push === null || push === 'unsupported' || push === 'install' || push === 'insecure' || push === 'denied'"
          :loading="pushBusy" @update:model-value="togglePush"
        />
        <div class="text-body-small text-medium-emphasis">{{ pushHint }}</div>
        <v-btn
          v-if="push === 'on'" variant="tonal" size="small" prepend-icon="mdi-bell-ring-outline" class="mt-2"
          :disabled="pushBusy" @click="sendTest"
        >
          {{ $t('settings.sendTest') }}
        </v-btn>

        <template v-if="auth.user?.is_admin">
          <div class="text-title-small mt-6 mb-1">{{ $t('admin.title') }}</div>
          <div class="text-body-small text-medium-emphasis mb-2">
            {{ $t('settings.adminHint') }}
          </div>
          <v-btn
            variant="tonal" size="small" prepend-icon="mdi-shield-account-outline" :to="{ name: 'admin' }"
            @click="open = false"
          >
            {{ $t('settings.openAdmin') }}
          </v-btn>
        </template>
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn @click="open = false">{{ $t('common.done') }}</v-btn>
      </template>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.swatch {
  width: 36px; height: 36px; border-radius: 18px; display: inline-flex; align-items: center; justify-content: center;
  transition: transform .1s;
}
.swatch:hover { transform: scale(1.1); }
</style>
