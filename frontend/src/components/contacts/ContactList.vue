<script setup lang="ts">
import { computed } from 'vue'
import type { Contact } from '@/api/types'
import { useContactRoute } from '@/composables/contactRoute'
import { useContactsStore } from '@/stores/contacts'
import { avatarColor, groupLetter, initials, realName, subtitle } from '@/utils/contact'

/** Contacts grouped by initial letter, or a hint if there are none */
const props = defineProps<{
  contacts: Contact[]
  title: string
  /** A search or category is active: "none found" instead of the getting-started hint */
  filtered: boolean
  /** Number of matches to show next to the title */
  matches?: number
}>()
const emit = defineEmits<{ create: []; import: []; dav: [] }>()

const store = useContactsStore()
const { selectedId, select } = useContactRoute()

type Row = { key: string; header: string } | { key: string; contact: Contact }
const rows = computed<Row[]>(() => {
  const out: Row[] = []
  let last = ''
  for (const c of props.contacts) {
    const letter = groupLetter(c)
    if (letter !== last) {
      out.push({ key: `h-${letter}`, header: letter })
      last = letter
    }
    out.push({ key: c.id, contact: c })
  }
  return out
})
</script>

<template>
  <div class="px-4 pt-4 pb-1 text-title-small text-medium-emphasis">
    {{ title }}
    <span v-if="matches !== undefined"> · {{ $t('contactsView.matches', matches) }}</span>
  </div>

  <div v-if="!store.loading && !contacts.length" class="empty pa-8 text-center">
    <template v-if="filtered">
      <v-icon icon="mdi-account-search-outline" size="56" class="mb-3 text-medium-emphasis" />
      <div class="text-body-large">{{ $t('contactsView.noneFound') }}</div>
    </template>
    <template v-else>
      <v-icon icon="mdi-account-box-multiple-outline" size="64" class="mb-3 text-medium-emphasis" />
      <div class="text-title-medium mb-1">{{ $t('contactsView.empty') }}</div>
      <div class="text-body-medium text-medium-emphasis mb-5">
        {{ $t('contactsView.emptyHint') }}
      </div>
      <div class="d-flex flex-wrap justify-center ga-2">
        <v-btn color="primary" variant="flat" prepend-icon="mdi-account-plus-outline" @click="emit('create')">{{ $t('contactsView.createContact') }}</v-btn>
        <v-btn variant="tonal" prepend-icon="mdi-upload-outline" @click="emit('import')">{{ $t('contactsView.importVcfButton') }}</v-btn>
        <v-btn variant="text" prepend-icon="mdi-sync" @click="emit('dav')">{{ $t('contactsView.setUpDav') }}</v-btn>
      </div>
    </template>
  </div>

  <v-virtual-scroll v-else :items="rows" item-key="key" class="rows">
    <template #default="{ item }">
      <div v-if="'header' in item" class="letter text-label-large">{{ item.header }}</div>
      <v-list-item
        v-else :active="item.contact.id === selectedId" color="secondary" rounded="xl" class="mx-2 contact-row"
        lines="two" @click="select(item.contact)"
      >
        <template #prepend>
          <v-avatar :color="avatarColor(item.contact)" size="40">
            <span class="text-label-large text-white">{{ initials(item.contact) }}</span>
          </v-avatar>
        </template>
        <v-list-item-title>{{ realName(item.contact) }}</v-list-item-title>
        <v-list-item-subtitle>{{ subtitle(item.contact) }}</v-list-item-subtitle>
      </v-list-item>
    </template>
  </v-virtual-scroll>
</template>

<style scoped>
.rows { flex: 1 1 auto; padding-bottom: 88px; }
.letter { padding: 12px 24px 4px; color: rgb(var(--v-theme-primary)); }
.contact-row { margin-block: 1px; }
.empty { max-width: 520px; margin-inline: auto; }
</style>
