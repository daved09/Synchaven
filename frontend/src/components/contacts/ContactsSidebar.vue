<script setup lang="ts">
import { computed, ref } from 'vue'
import { t } from '@/i18n'
import type { AddressBook } from '@/api/types'
import AddressBookDialog from '@/components/AddressBookDialog.vue'
import RenameCategoryDialog from '@/components/contacts/RenameCategoryDialog.vue'
import { useContactImport } from '@/composables/contactImport'
import { useContactRoute } from '@/composables/contactRoute'
import { useConfirmStore } from '@/stores/confirm'
import { useContactsStore } from '@/stores/contacts'
import { useNotifyStore } from '@/stores/notify'
import { sameCategory } from '@/utils/contact'

/** Filters of the contacts view: all contacts, address books and categories, each with its actions */
const emit = defineEmits<{
  /** The user picked a filter (closes the drawer on phones) */
  navigate: []
  dav: []
}>()

const contacts = useContactsStore()
const notify = useNotifyStore()
const confirm = useConfirmStore()
const { bookFilter, categoryFilter, filterBook, filterCategory } = useContactRoute()
const { pickAndImport } = useContactImport()

const totalCount = computed(() => contacts.books.reduce((n, b) => n + b.contact_count, 0))
const isCategoryShown = (name: string) => !!categoryFilter.value && sameCategory(categoryFilter.value, name)

function show(fn: () => void) {
  fn()
  emit('navigate')
}

// --- Address books ---
const bookDialog = ref(false)
const editBook = ref<AddressBook | null>(null)
function openBook(b: AddressBook | null) {
  editBook.value = b
  bookDialog.value = true
}
async function deleteBook(b: AddressBook) {
  if (!(await confirm.ask(t('contactsView.deleteBookTitle'), t('contactsView.deleteBookText', { name: b.name, n: b.contact_count }, b.contact_count)))) return
  try {
    await contacts.removeBook(b.id)
    if (bookFilter.value === b.id) show(() => filterBook(null))
    notify.info(t('contactsView.bookDeleted'))
  } catch (e) {
    notify.fail(e)
  }
}
async function exportBook(b: AddressBook) {
  try {
    await contacts.exportBook(b)
  } catch (e) {
    notify.fail(e)
  }
}

// --- Categories ---
const renameOpen = ref(false)
const renameFrom = ref('')
function openRename(name: string) {
  renameFrom.value = name
  renameOpen.value = true
}
function onRenamed(from: string, to: string) {
  if (isCategoryShown(from)) show(() => filterCategory(to))
}
async function deleteCategory(name: string, count: number) {
  if (!(await confirm.ask(t('contactsView.deleteCategoryTitle'), t('contactsView.deleteCategoryText', { name, n: count }, count)))) return
  try {
    await contacts.rewriteCategory(name, null)
    if (isCategoryShown(name)) show(() => filterBook(null))
    notify.info(t('contactsView.categoryDeleted'))
  } catch (e) {
    notify.fail(e)
  }
}
</script>

<template>
  <v-list nav density="comfortable" bg-color="transparent">
    <v-list-item
      rounded="xl" prepend-icon="mdi-account-multiple-outline" :title="$t('contactsView.all')" :active="!bookFilter && !categoryFilter"
      color="secondary" @click="show(() => filterBook(null))"
    >
      <template #append><span class="text-label-medium">{{ totalCount }}</span></template>
    </v-list-item>

    <div class="d-flex align-center pl-4 pr-2 mt-2">
      <v-list-subheader class="flex-grow-1 px-0">{{ $t('overview.addressBooks') }}</v-list-subheader>
      <v-btn icon="mdi-plus" size="small" variant="text" :title="$t('addressBook.new')" @click="openBook(null)" />
    </div>
    <v-list-item
      v-for="b in contacts.books" :key="b.id" rounded="xl" prepend-icon="mdi-book-account-outline" :title="b.name"
      :active="bookFilter === b.id" color="secondary" class="menu-item" @click="show(() => filterBook(b.id))"
    >
      <template #append>
        <span class="text-label-medium count">{{ b.contact_count }}</span>
        <v-menu location="end">
          <template #activator="{ props: p }">
            <v-btn v-bind="p" icon="mdi-dots-vertical" size="small" variant="text" class="item-menu" @click.stop />
          </template>
          <v-list density="compact" rounded="lg">
            <v-list-item prepend-icon="mdi-pencil-outline" :title="$t('common.edit')" @click="openBook(b)" />
            <v-list-item prepend-icon="mdi-upload-outline" :title="$t('contactsView.importVcf')" @click="pickAndImport(b)" />
            <v-list-item prepend-icon="mdi-download-outline" :title="$t('contactsView.exportVcf')" @click="exportBook(b)" />
            <v-divider />
            <v-list-item prepend-icon="mdi-delete-outline" :title="$t('common.delete')" base-color="error" @click="deleteBook(b)" />
          </v-list>
        </v-menu>
      </template>
    </v-list-item>

    <template v-if="contacts.categories.length">
      <div class="pl-4 pr-2 mt-2">
        <v-list-subheader class="px-0">{{ $t('contact.categories') }}</v-list-subheader>
      </div>
      <v-list-item
        v-for="cat in contacts.categories" :key="cat.name.toLowerCase()" rounded="xl" prepend-icon="mdi-label-outline"
        :title="cat.name" :active="isCategoryShown(cat.name)" color="secondary" class="menu-item"
        @click="show(() => filterCategory(cat.name))"
      >
        <template #append>
          <span class="text-label-medium count">{{ cat.count }}</span>
          <v-menu location="end">
            <template #activator="{ props: p }">
              <v-btn v-bind="p" icon="mdi-dots-vertical" size="small" variant="text" class="item-menu" @click.stop />
            </template>
            <v-list density="compact" rounded="lg">
              <v-list-item prepend-icon="mdi-pencil-outline" :title="$t('contactsView.rename')" @click="openRename(cat.name)" />
              <v-divider />
              <v-list-item
                prepend-icon="mdi-delete-outline" :title="$t('common.delete')" base-color="error" @click="deleteCategory(cat.name, cat.count)"
              />
            </v-list>
          </v-menu>
        </template>
      </v-list-item>
    </template>

    <v-divider class="my-3 mx-4" />
    <v-list-item rounded="xl" prepend-icon="mdi-sync" :title="$t('dav.title')" @click="emit('dav')" />
    <v-list-item rounded="xl" prepend-icon="mdi-calendar-month-outline" :title="$t('nav.calendar')" :to="{ name: 'calendar' }" />
  </v-list>
  <AddressBookDialog v-model="bookDialog" :book="editBook" />
  <RenameCategoryDialog v-model="renameOpen" :name="renameFrom" @renamed="onRenamed" />
</template>

<style scoped>
/* The count makes room for the ⋮ menu */
.menu-item:hover .count { display: none; }
@media (hover: none) { .menu-item .count { display: none; } }
</style>
