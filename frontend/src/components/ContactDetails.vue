<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import type { Contact } from '@/api/types'
import { useContactsStore } from '@/stores/contacts'
import {
  avatarColor, displayName, formatAddress, formatBirthday, initials, mapsUrl, realName, typeLabel, websiteUrl,
} from '@/utils/contact'

const props = defineProps<{ contact: Contact; closable?: boolean }>()
defineEmits<{ edit: []; delete: []; close: [] }>()

const contacts = useContactsStore()
const photo = ref<string | undefined>()

watch(
  () => [props.contact.id, props.contact.updated_at, props.contact.has_photo] as const,
  async ([id, , hasPhoto]) => {
    photo.value = hasPhoto ? contacts.photos.get(id) : undefined
    if (hasPhoto && !photo.value) {
      try {
        const p = await contacts.fetchPhoto(id)
        if (props.contact.id === id) photo.value = p
      } catch { /* photo is optional */ }
    }
  },
  { immediate: true },
)

const c = computed(() => props.contact)
const job = computed(() => [c.value.title, c.value.department, c.value.organization].filter(Boolean).join(' · '))
const bookName = computed(() => (contacts.books.length > 1 ? contacts.bookById.get(c.value.addressbook_id)?.name : ''))
const telHref = (v: string) => `tel:${v.replace(/[^\d+*#]/g, '')}`
const empty = computed(
  () => !c.value.phones.length && !c.value.emails.length && !c.value.addresses.length && !c.value.urls.length
    && !c.value.birthday && !c.value.note,
)
</script>

<template>
  <div class="details">
    <div class="d-flex justify-end ga-1 pa-2">
      <v-btn icon="mdi-pencil-outline" variant="text" title="Bearbeiten" @click="$emit('edit')" />
      <v-btn icon="mdi-delete-outline" variant="text" title="Löschen" @click="$emit('delete')" />
      <v-btn v-if="closable" icon="mdi-close" variant="text" title="Schließen" @click="$emit('close')" />
    </div>

    <div class="d-flex flex-column align-center text-center px-6 pb-4">
      <v-avatar size="112" :color="photo ? undefined : avatarColor(c)" class="mb-4">
        <v-img v-if="photo" :src="photo" cover :alt="displayName(c)" @error="photo = undefined" />
        <span v-else class="text-headline-medium text-white">{{ initials(c) }}</span>
      </v-avatar>
      <div class="text-headline-small">{{ realName(c) }}</div>
      <div v-if="c.nickname && c.nickname.trim() !== realName(c)" class="text-body-medium text-medium-emphasis">„{{ c.nickname }}“</div>
      <div v-if="job" class="text-body-medium text-medium-emphasis mt-1">{{ job }}</div>
      <div v-if="c.categories.length" class="d-flex flex-wrap justify-center ga-2 mt-3">
        <v-chip
          v-for="cat in c.categories" :key="cat" size="small" variant="tonal" prepend-icon="mdi-label-outline"
          :to="{ query: { category: cat, id: c.id } }"
        >
          {{ cat }}
        </v-chip>
      </div>

      <div class="d-flex ga-6 mt-5">
        <div v-if="c.phones[0]" class="quick">
          <v-btn icon="mdi-phone-outline" color="secondary-container" :href="telHref(c.phones[0].value)" title="Anrufen" />
          <span class="text-label-medium">Anrufen</span>
        </div>
        <div v-if="c.phones[0]" class="quick">
          <v-btn icon="mdi-message-text-outline" color="secondary-container" :href="`sms:${c.phones[0].value.replace(/[^\d+]/g, '')}`" title="Nachricht" />
          <span class="text-label-medium">SMS</span>
        </div>
        <div v-if="c.emails[0]" class="quick">
          <v-btn icon="mdi-email-outline" color="secondary-container" :href="`mailto:${c.emails[0].value}`" title="E-Mail schreiben" />
          <span class="text-label-medium">E-Mail</span>
        </div>
      </div>
    </div>

    <v-card v-if="!empty" color="surface-container" variant="flat" rounded="xl" class="mx-4 mb-4">
      <v-list bg-color="transparent" lines="two" density="comfortable">
        <v-list-item v-for="(p, i) in c.phones" :key="`p${i}`" :href="telHref(p.value)" :title="p.value" :subtitle="typeLabel(p.type, true)">
          <template #prepend><v-icon :icon="i === 0 ? 'mdi-phone-outline' : undefined" /></template>
        </v-list-item>
        <v-list-item v-for="(e, i) in c.emails" :key="`e${i}`" :href="`mailto:${e.value}`" :title="e.value" :subtitle="typeLabel(e.type)">
          <template #prepend><v-icon :icon="i === 0 ? 'mdi-email-outline' : undefined" /></template>
        </v-list-item>
        <v-list-item
          v-for="(a, i) in c.addresses" :key="`a${i}`" :href="mapsUrl(a)" target="_blank" rel="noopener" :subtitle="typeLabel(a.type)"
        >
          <template #prepend><v-icon :icon="i === 0 ? 'mdi-map-marker-outline' : undefined" /></template>
          <div class="text-body-large address">{{ formatAddress(a).join('\n') }}</div>
        </v-list-item>
        <v-list-item
          v-for="(u, i) in c.urls" :key="`u${i}`" :href="websiteUrl(u)" target="_blank" rel="noopener" :title="u.value"
          :subtitle="typeLabel(u.type)"
        >
          <template #prepend><v-icon :icon="i === 0 ? 'mdi-web' : undefined" /></template>
        </v-list-item>
        <v-list-item v-if="c.birthday" :title="formatBirthday(c.birthday)" subtitle="Geburtstag">
          <template #prepend><v-icon icon="mdi-cake-variant-outline" /></template>
        </v-list-item>
        <v-list-item v-if="c.note" subtitle="Notiz">
          <template #prepend><v-icon icon="mdi-text" /></template>
          <div class="text-body-large note">{{ c.note }}</div>
        </v-list-item>
      </v-list>
    </v-card>

    <div v-if="bookName" class="text-body-small text-medium-emphasis px-6 pb-6">
      <v-icon icon="mdi-book-account-outline" size="14" /> {{ bookName }}
    </div>
  </div>
</template>

<style scoped>
.quick { display: flex; flex-direction: column; align-items: center; gap: 6px; }
.address, .note { white-space: pre-line; overflow-wrap: anywhere; }
.details :deep(.v-list-item__prepend) { width: 48px; }
</style>
