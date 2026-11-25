<script setup lang="ts">
import { LANGUAGE_OPTIONS, language, t, type Language } from '@/i18n'
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
const resetTotp = ref(false)
/** Language of the new account's initial data */
const accountLanguage = ref<Language>(language)
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
  resetTotp.value = false
  accountLanguage.value = language
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
  email: (v: string) => /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(v.trim()) || t('userDialog.invalidEmail'),
  password: (v: string) => (!v && !isNew.value) || v.length >= 8 || t('common.minPassword'),
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
      if (resetTotp.value) data.reset_totp = true
      saved = await api.updateAdminUser(props.user.id, data)
      notify.info(password.value && !isOwn.value ? t('userDialog.savedSignedOut') : t('userDialog.saved'))
    } else {
      saved = await api.createAdminUser({
        name: name.value, email: email.value, password: password.value, is_admin: isAdmin.value,
        language: accountLanguage.value,
      })
      notify.info(t('userDialog.created', { email: saved.email }))
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
    <v-card :title="isNew ? $t('userDialog.new') : $t('userDialog.edit')" color="surface-container-high">
      <v-card-text>
        <v-form ref="formRef" @submit.prevent="save">
          <v-text-field v-model="name" :label="$t('common.name')" prepend-inner-icon="mdi-account-outline" :hint="isNew ? $t('userDialog.nameHint') : ''" />
          <v-text-field
            v-model="email" :label="$t('common.email')" type="email" prepend-inner-icon="mdi-email-outline" :rules="[rules.email]"
            autocomplete="off"
          />
          <v-text-field
            v-model="password" :label="isNew ? $t('common.password') : $t('profile.newPassword')"
            :type="showPassword ? 'text' : 'password'" autocomplete="new-password" prepend-inner-icon="mdi-lock-outline"
            :rules="[rules.password]" persistent-hint
            :hint="isNew ? $t('userDialog.passwordHintNew') : isOwn ? $t('userDialog.passwordHintOwn') : $t('userDialog.passwordHintChange')"
          >
            <template #append-inner>
              <v-icon
                :icon="showPassword ? 'mdi-eye-off-outline' : 'mdi-eye-outline'" :title="showPassword ? $t('userDialog.hide') : $t('userDialog.show')"
                @click="showPassword = !showPassword"
              />
              <v-icon icon="mdi-dice-multiple-outline" :title="$t('userDialog.generate')" class="ml-2" @click="generate" />
            </template>
          </v-text-field>
          <v-select
            v-if="isNew" v-model="accountLanguage" :items="LANGUAGE_OPTIONS" :label="$t('settings.language')"
            prepend-inner-icon="mdi-translate" :hint="$t('userDialog.languageHint')" persistent-hint class="mt-1"
          />
          <v-checkbox
            v-model="isAdmin" :label="$t('userDialog.admin')" color="primary" hide-details class="mt-2" :disabled="isOwn"
            :messages="isOwn ? $t('userDialog.ownRights') : undefined"
          />
          <div class="text-body-small text-medium-emphasis">
            {{ $t('userDialog.adminHint') }}
          </div>
          <template v-if="user?.totp_enabled">
            <v-checkbox v-model="resetTotp" :label="$t('userDialog.resetTotp')" color="error" hide-details class="mt-2" />
            <div class="text-body-small text-medium-emphasis">{{ $t('userDialog.resetTotpHint') }}</div>
          </template>
        </v-form>
      </v-card-text>
      <template #actions>
        <v-spacer />
        <v-btn @click="open = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="primary" variant="flat" :loading="saving" @click="save">{{ isNew ? $t('common.create') : $t('common.save') }}</v-btn>
      </template>
    </v-card>
  </v-dialog>
</template>
