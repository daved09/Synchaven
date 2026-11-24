<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { api } from '@/api/client'
import { useAuthStore } from '@/stores/auth'

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

onMounted(async () => {
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
    if (isRegister.value) await auth.register(email.value, password.value, name.value)
    else await auth.login(email.value, password.value)
    router.replace((route.query.redirect as string) || '/')
  } catch (e) {
    error.value = e instanceof Error ? e.message : 'Anmeldung fehlgeschlagen'
  } finally {
    loading.value = false
  }
}
const rules = {
  email: (v: string) => /.+@.+\..+/.test(v) || 'Bitte eine gültige E-Mail-Adresse eingeben',
  password: (v: string) => !isRegister.value || v.length >= 8 || 'Mindestens 8 Zeichen',
  required: (v: string) => !!v || 'Pflichtfeld',
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
          <div class="text-headline-small">{{ isRegister ? 'Konto erstellen' : 'Anmelden' }}</div>
          <div class="text-body-medium text-medium-emphasis mt-1">
            {{ isRegister ? 'Lege ein Konto für deinen Kalender an' : 'Weiter zu deinem Kalender' }}
          </div>
        </v-card-item>
        <v-card-text>
          <v-form ref="formRef" @submit.prevent="submit">
            <v-text-field v-if="isRegister" v-model="name" label="Name" autocomplete="name" prepend-inner-icon="mdi-account-outline" />
            <v-text-field
              v-model="email" label="E-Mail" type="email" autocomplete="email" prepend-inner-icon="mdi-email-outline"
              :rules="[rules.required, rules.email]" autofocus
            />
            <v-text-field
              v-model="password" label="Passwort" :type="showPassword ? 'text' : 'password'"
              :autocomplete="isRegister ? 'new-password' : 'current-password'" prepend-inner-icon="mdi-lock-outline"
              :append-inner-icon="showPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'"
              :rules="[rules.required, rules.password]" @click:append-inner="showPassword = !showPassword"
            />
            <v-alert v-if="error" type="error" variant="tonal" density="compact" class="mb-4">{{ error }}</v-alert>
            <v-btn type="submit" color="primary" size="large" block :loading="loading">
              {{ isRegister ? 'Registrieren' : 'Anmelden' }}
            </v-btn>
          </v-form>
        </v-card-text>
        <v-card-actions v-if="allowRegistration || isRegister" class="justify-center pb-4">
          <span class="text-body-medium text-medium-emphasis">{{ isRegister ? 'Bereits registriert?' : 'Noch kein Konto?' }}</span>
          <v-btn :to="isRegister ? '/login' : '/register'" variant="text" color="primary">
            {{ isRegister ? 'Anmelden' : 'Registrieren' }}
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
