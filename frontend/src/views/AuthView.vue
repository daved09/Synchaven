<script setup lang="ts">
import { LANGUAGE_OPTIONS, language, t, type Language } from '@/i18n'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api/client'
import { useAuthStore } from '@/stores/auth'
import { useSettingsStore } from '@/stores/settings'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const isRegister = computed(() => route.name === 'register')
const allowRegistration = ref(true)
const email = ref('')
const password = ref('')
const name = ref('')
const showPassword = ref(false)
const loading = ref(false)
const error = ref('')
const formRef = ref()

// The UI language is fixed for the page's lifetime, so choosing another one reloads it.
// What was already typed (except the password) survives the reload.
const DRAFT_KEY = 'synchaven.authDraft'
const { settings } = useSettingsStore()
function chooseLanguage(v: Language) {
  sessionStorage.setItem(DRAFT_KEY, JSON.stringify({ name: name.value, email: email.value }))
  settings.language = v
  // The settings store writes to localStorage in a watcher – wait for it before reloading
  setTimeout(() => location.reload())
}

onMounted(async () => {
  const draft = sessionStorage.getItem(DRAFT_KEY)
  if (draft) {
    sessionStorage.removeItem(DRAFT_KEY)
    try {
      const d = JSON.parse(draft)
      name.value = d.name ?? ''
      email.value = d.email ?? ''
    } catch { /* ignore */ }
  }
  try {
    allowRegistration.value = (await api.info()).allow_registration
  } catch { /* server may be unreachable – the error shows up on submit */ }
})

async function submit() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  loading.value = true
  error.value = ''
  try {
    if (isRegister.value) await auth.register(email.value, password.value, name.value, language)
    else await auth.login(email.value, password.value)
    router.replace((route.query.redirect as string) || '/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : t('auth.loginFailed')
  } finally {
    loading.value = false
  }
}
const rules = {
  email: (v: string) => /.+@.+\..+/.test(v) || t('auth.invalidEmail'),
  password: (v: string) => !isRegister.value || v.length >= 8 || t('common.minPassword'),
  required: (v: string) => !!v || t('common.required'),
}
</script>

<template>
  <v-main class="auth-bg">
    <div class="wrap">
      <v-card class="auth-card pa-2" color="surface-container-low" elevation="0" rounded="xl">
        <v-card-item class="text-center pt-6">
          <v-avatar color="primary-container" size="64" class="mb-4">
            <v-icon icon="mdi-calendar-month" size="34" color="on-primary-container" />
          </v-avatar>
          <div class="text-headline-small">{{ isRegister ? $t('auth.createAccount') : $t('auth.signIn') }}</div>
          <div class="text-body-medium text-medium-emphasis mt-1">
            {{ isRegister ? $t('auth.registerSubtitle') : $t('auth.signInSubtitle') }}
          </div>
        </v-card-item>
        <v-card-text>
          <v-form ref="formRef" @submit.prevent="submit">
            <v-select
              v-if="isRegister" :model-value="language" :items="LANGUAGE_OPTIONS" :label="$t('settings.language')"
              prepend-inner-icon="mdi-translate" :hint="$t('auth.languageHint')" persistent-hint class="mb-6"
              @update:model-value="chooseLanguage"
            />
            <v-text-field v-if="isRegister" v-model="name" :label="$t('common.name')" autocomplete="name" prepend-inner-icon="mdi-account-outline" />
            <v-text-field
              v-model="email" :label="$t('common.email')" type="email" autocomplete="email" prepend-inner-icon="mdi-email-outline"
              :rules="[rules.required, rules.email]" autofocus
            />
            <v-text-field
              v-model="password" :label="$t('common.password')" :type="showPassword ? 'text' : 'password'"
              :autocomplete="isRegister ? 'new-password' : 'current-password'" prepend-inner-icon="mdi-lock-outline"
              :append-inner-icon="showPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
              :rules="[rules.required, rules.password]" @click:append-inner="showPassword = !showPassword"
            />
            <v-alert v-if="error" type="error" variant="tonal" density="compact" class="mb-4">{{ error }}</v-alert>
            <v-btn type="submit" color="primary" size="large" block :loading="loading">
              {{ isRegister ? $t('auth.register') : $t('auth.signIn') }}
            </v-btn>
          </v-form>
        </v-card-text>
        <v-card-actions v-if="allowRegistration || isRegister" class="justify-center pb-4">
          <span class="text-body-medium text-medium-emphasis">{{ isRegister ? $t('auth.haveAccount') : $t('auth.noAccount') }}</span>
          <v-btn :to="isRegister ? '/login' : '/register'" variant="text" color="primary">
            {{ isRegister ? $t('auth.signIn') : $t('auth.register') }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </div>
  </v-main>
</template>

<style scoped>
.auth-bg { background: rgb(var(--v-theme-surface-container)); min-height: 100dvh; }
.wrap { min-height: 100dvh; display: flex; align-items: center; justify-content: center; padding: 16px; }
.auth-card { width: 100%; max-width: 420px; }
</style>
