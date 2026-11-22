<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '@/stores/auth'
import { useNotifyStore } from '@/stores/notify'

const open = defineModel<boolean>({ required: true })
const auth = useAuthStore()
const notify = useNotifyStore()

const url = computed(() => `${window.location.origin}/dav/`)
const insecure = window.location.protocol !== 'https:' && !['localhost', '127.0.0.1'].includes(window.location.hostname)

async function copy(text: string) {
  try {
    await navigator.clipboard.writeText(text)
    notify.info('In die Zwischenablage kopiert')
  } catch {
    notify.fail(new Error('Kopieren nicht möglich – bitte den Text markieren'))
  }
}
</script>

<template>
  <v-dialog v-model="open" max-width="560" scrollable>
    <v-card title="Mit DAVx⁵ synchronisieren" color="surface-container-high">
      <v-card-text>
        <p class="text-body-medium mb-4">
          Die Kontakte lassen sich per CardDAV mit Android (DAVx⁵), Thunderbird oder iPhone/iPad abgleichen.
          Änderungen auf dem Handy erscheinen hier und umgekehrt.
        </p>

        <v-text-field :model-value="url" label="Basis-URL" readonly variant="outlined" class="mb-1">
          <template #append-inner>
            <v-btn icon="mdi-content-copy" size="small" variant="text" title="Kopieren" @click="copy(url)" />
          </template>
        </v-text-field>
        <v-text-field :model-value="auth.user?.email" label="Benutzername" readonly variant="outlined" class="mb-1">
          <template #append-inner>
            <v-btn icon="mdi-content-copy" size="small" variant="text" title="Kopieren" @click="copy(auth.user?.email ?? '')" />
          </template>
        </v-text-field>
        <v-text-field model-value="Ihr Anmeldepasswort" label="Passwort" readonly variant="outlined" disabled />

        <v-alert v-if="insecure" type="warning" variant="tonal" density="compact" class="mb-4">
          Die Verbindung ist nicht verschlüsselt. Für die Synchronisierung über das Internet sollte ein
          Reverse-Proxy mit HTTPS vorgeschaltet werden, da das Passwort bei jeder Anfrage übertragen wird.
        </v-alert>

        <div class="text-title-small mb-2">So geht’s in DAVx⁵</div>
        <ol class="text-body-medium steps">
          <li>DAVx⁵ öffnen und unten rechts auf <b>+</b> tippen.</li>
          <li><b>Mit URL und Benutzername anmelden</b> wählen.</li>
          <li>Basis-URL, Benutzername und Passwort von oben eintragen und <b>Anmelden</b> tippen.</li>
          <li>Kontonamen bestätigen (am besten die E-Mail-Adresse) und <b>Konto anlegen</b>.</li>
          <li>Im Reiter <b>CardDAV</b> die gewünschten Adressbücher aktivieren.</li>
        </ol>
        <p class="text-body-small text-medium-emphasis mt-3">
          Thunderbird und iOS finden die Adressbücher mit derselben Adresse automatisch
          (iOS: Einstellungen → Kontakte → Accounts → Andere → CardDAV-Account).
        </p>
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn @click="open = false">Fertig</v-btn>
      </template>
    </v-card>
  </v-dialog>
</template>

<style scoped>
.steps { padding-left: 20px; }
.steps li { margin-bottom: 4px; }
</style>
