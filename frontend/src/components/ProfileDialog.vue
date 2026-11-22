<script setup lang="ts">
import { ref, watch } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'

const open = defineModel<boolean>({ required: true })
const auth = useAuthStore()
const notify = useNotifyStore()

const name = ref('')
const email = ref('')
const currentPassword = ref('')
const newPassword = ref('')
const saving = ref(false)
const formRef = ref()

watch(open, (v) => {
  if (!v) return
  name.value = auth.user?.name ?? ''
  email.value = auth.user?.email ?? ''
  currentPassword.value = ''
  newPassword.value = ''
})

const needsPassword = () => email.value.trim().toLowerCase() !== auth.user?.email || !!newPassword.value

async function save() {
  const { valid } = await formRef.value.validate()
  if (!valid) return
  saving.value = true
  try {
    await auth.update({
      name: name.value,
      email: email.value,
      current_password: currentPassword.value || undefined,
      new_password: newPassword.value || undefined,
    })
    notify.info('Profil gespeichert')
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
    <v-card title="Profil" color="surface-container-high">
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="save">
          <v-text-field v-model="name" label="Name" prepend-inner-icon="mdi-account-outline" />
          <v-text-field v-model="email" label="E-Mail" type="email" prepend-inner-icon="mdi-email-outline" />
          <v-divider class="mb-4" />
          <v-text-field
            v-model="newPassword" label="Neues Passwort (optional)" type="password" autocomplete="new-password"
            prepend-inner-icon="mdi-lock-reset" :rules="[(v: string) => !v || v.length >= 8 || 'Mindestens 8 Zeichen']"
          />
          <v-text-field
            v-model="currentPassword" label="Aktuelles Passwort" type="password" autocomplete="current-password"
            prepend-inner-icon="mdi-lock-outline"
            :hint="'Erforderlich zum Ändern von E-Mail oder Passwort'" persistent-hint
            :rules="[(v: string) => !needsPassword() || !!v || 'Bitte aktuelles Passwort eingeben']"
          />
        </v-form>
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn @click="open = false">Abbrechen</v-btn>
        <v-btn color="primary" variant="flat" :loading="saving" @click="save">Speichern</v-btn>
      </template>
    </v-card>
  </v-dialog>
</template>
