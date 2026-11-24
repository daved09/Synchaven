<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { AdminUser, AdminUserInput } from '@/api/types'
import { useNotifyStore } from '@/stores/notify'

const props = defineProps<{ user: AdminUser | null; ownId: string | undefined }>()
const emit = defineEmits<{ saved: [user: AdminUser] }>()
const open = defineModel<boolean>({ required: true })
const notify = useNotifyStore()

const name = ref('')
const email = ref('')
const password = ref('')
const isAdmin = ref(false)
const showPassword = ref(false)
const saving = ref(false)
const formRef = ref()

const isNew = computed(() => !props.user)
const isOwn = computed(() => !!props.user && props.user.id === props.ownId)

watch(open, (v) => {
  if (!v) return
  name.value = props.user?.name ?? ''
  email.value = props.user?.email ?? ''
  password.value = ''
  isAdmin.value = props.user?.is_admin ?? false
  showPassword.value = false
})

/** Random password to hand out, without easily confused characters */
function generate() {
  const chars = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'
  const bytes = crypto.getRandomValues(new Uint8Array(14))
  password.value = Array.from(bytes, (b) => chars[b % chars.length]).join('')
  showPassword.value = true
}

const rules = {
  email: (v: string) => /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v.trim()) || 'Gültige E-Mail-Adresse eingeben',
  password: (v: string) => (!v && !isNew.value) || v.length >= 8 || 'Mindestens 8 Zeichen',
}

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  saving.value = true
  try {
    let saved: AdminUser
    if (props.user) {
      const data: AdminUserInput = { name: name.value, email: email.value }
      if (password.value) data.password = password.value
      if (!isOwn.value) data.is_admin = isAdmin.value
      saved = await api.updateAdminUser(props.user.id, data)
      notify.info(password.value && !isOwn.value ? 'Gespeichert – das Konto wurde auf allen Geräten abgemeldet' : 'Gespeichert')
    } else {
      saved = await api.createAdminUser({
        name: name.value, email: email.value, password: password.value, is_admin: isAdmin.value,
      })
      notify.info(`Konto ${saved.email} angelegt`)
    }
    emit('saved', saved)
    open.value = false
  } catch (e) {
    notify.fail(e)
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <v-dialog v-model="open" max-width="480">
    <v-card :title="isNew ? 'Neues Konto' : 'Konto bearbeiten'" color="surface-container-high">
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="save">
          <v-text-field v-model="name" label="Name" prepend-inner-icon="mdi-account-outline" :hint="isNew ? 'Ohne Angabe der Teil vor dem @' : ''" />
          <v-text-field
            v-model="email" label="E-Mail" type="email" prepend-inner-icon="mdi-email-outline" :rules="[rules.email]"
            autocomplete="off"
          />
          <v-text-field
            v-model="password" :label="isNew ? 'Passwort' : 'Neues Passwort (optional)'"
            :type="showPassword ? 'text' : 'password'" autocomplete="new-password" prepend-inner-icon="mdi-lock-outline"
            :rules="[rules.password]" persistent-hint
            :hint="isNew ? 'Gib es dem Benutzer weiter; ändern kann er es im Profil.'
              : isOwn ? '' : 'Meldet das Konto auf allen Geräten ab. CardDAV-Clients brauchen danach das neue Passwort.'"
          >
            <template #append-inner>
              <v-icon
                :icon="showPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'" :title="showPassword ? 'Verbergen' : 'Anzeigen'"
                @click="showPassword = !showPassword"
              />
              <v-icon icon="mdi-dice-multiple-outline" title="Passwort erzeugen" class="ml-2" @click="generate" />
            </template>
          </v-text-field>
          <v-checkbox
            v-model="isAdmin" label="Administrator" color="primary" hide-details class="mt-2" :disabled="isOwn"
            :messages="isOwn ? 'Die eigenen Rechte lassen sich nicht entziehen' : undefined"
          />
          <div class="text-body-small text-medium-emphasis">
            Administratoren dürfen diese Verwaltung öffnen: Konten bearbeiten, Einstellungen ändern und die ganze
            Datenbank exportieren und ersetzen.
          </div>
        </v-form>
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn @click="open = false">Abbrechen</v-btn>
        <v-btn color="primary" variant="flat" :loading="saving" @click="save">{{ isNew ? 'Anlegen' : 'Speichern' }}</v-btn>
      </template>
    </v-card>
  </v-dialog>
</template>
