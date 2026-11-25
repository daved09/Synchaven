<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { t as tr } from '@/i18n'
import { useRoute, useRouter } from 'vue-router'
import { useDisplay } from 'vuetify'
import type { AddressBook, Contact } from '@/api/types'
import AccountMenu from '@/components/AccountMenu.vue'
import AddressBookDialog from '@/components/AddressBookDialog.vue'
import ConfirmDialog from '@/components/ConfirmDialog.vue'
import ContactDetails from '@/components/ContactDetails.vue'
import ContactDialog from '@/components/ContactDialog.vue'
import DavSetupDialog from '@/components/DavSetupDialog.vue'
import { useAuthStore } from '@/stores/auth'
import { useContactsStore } from '@/stores/contacts'
import { useNotifyStore } from '@/stores/notify'
import { avatarColor, displayName, groupLetter, initials, matches, realName, sameCategory, subtitle } from '@/utils/contact'

const route = useRoute()
const router = useRouter()
const { mdAndUp, lgAndUp, smAndDown } = useDisplay()
const auth = useAuthStore()
const contacts = useContactsStore()
const notify = useNotifyStore()

const drawer = ref(mdAndUp.value)
const search = ref('')
const searchField = ref<HTMLElement & { focus: () => void }>()
const confirmRef = ref<InstanceType<typeof ConfirmDialog>>()
const accountMenu = ref<InstanceType<typeof AccountMenu>>()
const importInput = ref<HTMLInputElement>()
let importTarget: AddressBook | null = null

const editorOpen = ref(false)
const editId = ref<string | null>(null)
const bookDialog = ref(false)
const editBook = ref<AddressBook | null>(null)
const davOpen = ref(false)
const dragOver = ref(false)
const renameOpen = ref(false)
const renameFrom = ref('')
const renameTo = ref('')
const renaming = ref(false)

// --- Selection via the URL (?book=…|?category=…&id=…), so links and "Back" work ---
const bookFilter = computed(() => (route.query.book as string | undefined) ?? null)
const categoryFilter = computed(() => (route.query.category as string | undefined) ?? null)
const selectedId = computed(() => (route.query.id as string | undefined) ?? null)
const selected = computed(() => contacts.list.find((c) => c.id === selectedId.value) ?? null)
const mobileDetails = computed({
  get: () => smAndDown.value && !!selected.value,
  set: (v) => !v && select(null),
})

function select(c: Contact | null) {
  const query = { ...route.query }
  if (c) query.id = c.id
  else delete query.id
  if (c && smAndDown.value && !selectedId.value) router.push({ query })
  else router.replace({ query })
}
function setFilter(query: { book?: string; category?: string }) {
  router.replace({ query })
  if (smAndDown.value) drawer.value = false
}
const filterBook = (id: string | null) => setFilter(id ? { book: id } : {})
const filterCategory = (name: string) => setFilter({ category: name })

const filtered = computed(() =>
  contacts.sorted.filter(
    (c) => (!bookFilter.value || c.addressbook_id === bookFilter.value)
      && (!categoryFilter.value || c.categories.some((x) => sameCategory(x, categoryFilter.value!)))
      && matches(c, search.value),
  ),
)
const totalCount = computed(() => contacts.books.reduce((n, b) => n + b.contact_count, 0))
const currentBook = computed(() => (bookFilter.value ? contacts.bookById.get(bookFilter.value) ?? null : null))

type Row = { key: string; header: string } | { key: string; contact: Contact }
const rows = computed<Row[]>(() => {
  const out: Row[] = []
  let last = ''
  for (const c of filtered.value) {
    const letter = groupLetter(c)
    if (letter !== last) {
      out.push({ key: `h-${letter}`, header: letter })
      last = letter
    }
    out.push({ key: c.id, contact: c })
  }
  return out
})

onMounted(async () => {
  try {
    await Promise.all([auth.fetchMe(), contacts.load()])
  } catch (e) {
    notify.fail(e)
  }
  window.addEventListener('keydown', onKey)
})
onBeforeUnmount(() => window.removeEventListener('keydown', onKey))

// Remove a deleted or unknown selection
watch(() => [selectedId.value, contacts.list.length], () => {
  if (selectedId.value && !contacts.loading && contacts.list.length && !selected.value) select(null)
})

// --- Contacts ---
function newContact() {
  editId.value = null
  editorOpen.value = true
}
function editContact(c: Contact) {
  editId.value = c.id
  editorOpen.value = true
}
function onSaved(c: Contact) {
  if (bookFilter.value && c.addressbook_id !== bookFilter.value) filterBook(null)
  if (categoryFilter.value && !c.categories.some((x) => sameCategory(x, categoryFilter.value!))) filterBook(null)
  setTimeout(() => select(c))
}
async function deleteContact(c: Contact) {
  if (!(await confirmRef.value!.ask(tr('contactsView.deleteContactTitle'), tr('contactsView.deleteContactText', { name: displayName(c) })))) return
  try {
    await contacts.remove(c.id)
    select(null)
    notify.info(tr('contactsView.contactDeleted'))
  } catch (e) {
    notify.fail(e)
  }
}

// --- Address books ---
function newBook() {
  editBook.value = null
  bookDialog.value = true
}
function openBook(b: AddressBook) {
  editBook.value = b
  bookDialog.value = true
}
async function deleteBook(b: AddressBook) {
  if (!(await confirmRef.value!.ask(tr('contactsView.deleteBookTitle'), tr('contactsView.deleteBookText', { name: b.name, n: b.contact_count }, b.contact_count)))) return
  try {
    await contacts.removeBook(b.id)
    if (bookFilter.value === b.id) filterBook(null)
    notify.info(tr('contactsView.bookDeleted'))
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
function startImport(b?: AddressBook) {
  importTarget = b ?? currentBook.value ?? contacts.books[0] ?? null
  if (!importTarget) return
  importInput.value!.value = ''
  importInput.value!.click()
}
async function importFiles(files: File[], book: AddressBook | null) {
  if (!book || !files.length) return
  try {
    let created = 0
    let updated = 0
    let unchanged = 0
    let skipped = 0
    for (const f of files) {
      const r = await contacts.importFile(book, f)
      created += r.created
      updated += r.updated
      unchanged += r.unchanged
      skipped += r.skipped.length
    }
    const parts = [
      tr('contactsView.importNew', { n: created }),
      updated ? tr('contactsView.importUpdated', { n: updated }) : '',
      unchanged ? tr('contactsView.importUnchanged', { n: unchanged }) : '',
      skipped ? tr('contactsView.importSkipped', { n: skipped }) : '',
    ]
    notify.info(tr('contactsView.importResult', { name: book.name, parts: parts.filter(Boolean).join(', ') }))
  } catch (e) {
    notify.fail(e)
  }
}
function onImportFile(e: Event) {
  importFiles([...((e.target as HTMLInputElement).files ?? [])], importTarget)
}
function onDrop(e: DragEvent) {
  dragOver.value = false
  const files = [...(e.dataTransfer?.files ?? [])].filter((f) => /\.vcf$|\.vcard$/i.test(f.name) || f.type.includes('vcard'))
  if (!files.length) {
    notify.fail(new Error(tr('contactsView.dropVcf')))
    return
  }
  importFiles(files, currentBook.value ?? contacts.books[0] ?? null)
}

// --- Categories ---
function openRename(name: string) {
  renameFrom.value = name
  renameTo.value = name
  renameOpen.value = true
}
async function renameCategory() {
  const to = renameTo.value.trim()
  if (!to || to === renameFrom.value) {
    renameOpen.value = false
    return
  }
  renaming.value = true
  try {
    await contacts.rewriteCategory(renameFrom.value, to)
    if (categoryFilter.value && sameCategory(categoryFilter.value, renameFrom.value)) setFilter({ category: to })
    renameOpen.value = false
    notify.info(tr('contactsView.categoryRenamed'))
  } catch (e) {
    notify.fail(e)
  } finally {
    renaming.value = false
  }
}
async function deleteCategory(name: string, count: number) {
  if (!(await confirmRef.value!.ask(
    tr('contactsView.deleteCategoryTitle'), tr('contactsView.deleteCategoryText', { name, n: count }, count),
  ))) return
  try {
    await contacts.rewriteCategory(name, null)
    if (categoryFilter.value && sameCategory(categoryFilter.value, name)) filterBook(null)
    notify.info(tr('contactsView.categoryDeleted'))
  } catch (e) {
    notify.fail(e)
  }
}

// --- Keyboard shortcuts ---
const anyDialog = () => editorOpen.value || bookDialog.value || davOpen.value || renameOpen.value || !!accountMenu.value?.dialogOpen
function onKey(e: KeyboardEvent) {
  const t = e.target as HTMLElement
  if (e.ctrlKey || e.metaKey || e.altKey || anyDialog()) return
  if (t.closest('input, textarea, select, [contenteditable="true"], .v-overlay')) return
  const k = e.key.toLowerCase()
  if (k === 'c' || k === 'n') {
    e.preventDefault()
    newContact()
  } else if (k === '/') {
    e.preventDefault()
    searchField.value?.focus()
  } else if (k === 'e' && selected.value) {
    e.preventDefault()
    editContact(selected.value)
  }
}
</script>

<template>
  <v-navigation-drawer
    v-model="drawer" :temporary="smAndDown" :mobile="smAndDown" width="300" color="surface-container-low" border="0"
  >
    <div class="pa-4 pb-2">
      <v-btn
        color="primary-container" size="x-large" rounded="lg" class="create-btn" prepend-icon="mdi-account-plus-outline"
        elevation="1" @click="newContact"
      >
        {{ $t('contact.new') }}
      </v-btn>
    </div>
    <v-list nav density="comfortable" bg-color="transparent">
      <v-list-item
        rounded="xl" prepend-icon="mdi-account-multiple-outline" :title="$t('contactsView.all')" :active="!bookFilter && !categoryFilter"
        color="secondary" @click="filterBook(null)"
      >
        <template #append><span class="text-label-medium">{{ totalCount }}</span></template>
      </v-list-item>

      <div class="d-flex align-center pl-4 pr-2 mt-2">
        <v-list-subheader class="flex-grow-1 px-0">{{ $t('overview.addressBooks') }}</v-list-subheader>
        <v-btn icon="mdi-plus" size="small" variant="text" :title="$t('addressBook.new')" @click="newBook" />
      </div>
      <v-list-item
        v-for="b in contacts.books" :key="b.id" rounded="xl" prepend-icon="mdi-book-account-outline" :title="b.name"
        :active="bookFilter === b.id" color="secondary" class="book-item" @click="filterBook(b.id)"
      >
        <template #append>
          <span class="text-label-medium count">{{ b.contact_count }}</span>
          <v-menu location="end">
            <template #activator="{ props: p }">
              <v-btn v-bind="p" icon="mdi-dots-vertical" size="small" variant="text" class="book-menu" @click.stop />
            </template>
            <v-list density="compact" rounded="lg">
              <v-list-item prepend-icon="mdi-pencil-outline" :title="$t('common.edit')" @click="openBook(b)" />
              <v-list-item prepend-icon="mdi-upload-outline" :title="$t('contactsView.importVcf')" @click="startImport(b)" />
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
          :title="cat.name" :active="!!categoryFilter && sameCategory(categoryFilter, cat.name)" color="secondary"
          class="book-item" @click="filterCategory(cat.name)"
        >
          <template #append>
            <span class="text-label-medium count">{{ cat.count }}</span>
            <v-menu location="end">
              <template #activator="{ props: p }">
                <v-btn v-bind="p" icon="mdi-dots-vertical" size="small" variant="text" class="book-menu" @click.stop />
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
      <v-list-item rounded="xl" prepend-icon="mdi-sync" :title="$t('dav.title')" @click="davOpen = true" />
      <v-list-item rounded="xl" prepend-icon="mdi-calendar-month-outline" :title="$t('nav.calendar')" :to="{ name: 'calendar' }" />
    </v-list>
    <input ref="importInput" type="file" accept=".vcf,.vcard,text/vcard,text/x-vcard" multiple hidden @change="onImportFile" />
  </v-navigation-drawer>

  <v-app-bar flat color="surface" density="comfortable">
    <v-app-bar-nav-icon :title="$t('common.menu')" @click="drawer = !drawer" />
    <div v-if="lgAndUp" class="d-flex align-center mr-6 brand">
      <v-icon icon="mdi-account-box-multiple" color="primary" size="28" class="mr-2" />
      <span class="text-title-large">{{ $t('nav.contacts') }}</span>
    </div>
    <v-text-field
      ref="searchField" v-model="search" :placeholder="$t('contactsView.search')" density="compact" variant="solo-filled"
      flat rounded="xl" hide-details clearable prepend-inner-icon="mdi-magnify" class="search mx-2"
      bg-color="surface-container-high" @keydown.esc="search = ''"
    />
    <v-progress-circular v-if="contacts.loading" indeterminate size="20" width="2" color="primary" class="mx-2" />
    <v-btn v-if="!smAndDown" icon="mdi-upload-outline" :title="$t('contactsView.importContacts')" @click="startImport()" />
    <v-btn icon="mdi-calendar-month-outline" :title="$t('nav.calendar')" :to="{ name: 'calendar' }" />
    <AccountMenu ref="accountMenu" />
  </v-app-bar>

  <v-main class="main">
    <div
      class="view-surface d-flex" :class="{ 'drag-over': dragOver }"
      @dragover.prevent="dragOver = true" @dragleave.self="dragOver = false" @drop.prevent="onDrop"
    >
      <div class="list-pane" :class="{ narrow: mdAndUp }">
        <div class="px-4 pt-4 pb-1 text-title-small text-medium-emphasis">
          {{ currentBook?.name ?? categoryFilter ?? $t('contactsView.all') }}
          <span v-if="search"> · {{ filtered.length }} Treffer</span>
        </div>

        <div v-if="!contacts.loading && !filtered.length" class="empty pa-8 text-center">
          <template v-if="search || categoryFilter">
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
              <v-btn color="primary" variant="flat" prepend-icon="mdi-account-plus-outline" @click="newContact">{{ $t('contactsView.createContact') }}</v-btn>
              <v-btn variant="tonal" prepend-icon="mdi-upload-outline" @click="startImport()">{{ $t('contactsView.importVcfButton') }}</v-btn>
              <v-btn variant="text" prepend-icon="mdi-sync" @click="davOpen = true">{{ $t('contactsView.setUpDav') }}</v-btn>
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
      </div>

      <div v-if="mdAndUp" class="detail-pane">
        <ContactDetails
          v-if="selected" :key="selected.id" :contact="selected"
          @edit="editContact(selected)" @delete="deleteContact(selected)"
        />
        <div v-else-if="contacts.list.length" class="empty fill-height d-flex flex-column align-center justify-center text-medium-emphasis">
          <v-icon icon="mdi-card-account-details-outline" size="72" class="mb-3" />
          <div class="text-body-large">{{ $t('contactsView.select') }}</div>
        </div>
      </div>
      <div v-if="dragOver" class="drop-hint">
        <v-icon icon="mdi-file-upload-outline" size="48" class="mb-2" />
        <div class="text-title-medium">.vcf-Datei hier ablegen</div>
        <div class="text-body-medium">{{ $t('contactsView.dropTarget', { name: currentBook?.name ?? contacts.books[0]?.name }) }}</div>
      </div>
    </div>
    <v-btn
      v-if="smAndDown" class="fab" color="primary-container" size="x-large" icon="mdi-account-plus-outline" rounded="lg"
      elevation="3" :title="$t('contactsView.createContact')" @click="newContact"
    />
  </v-main>

  <v-dialog v-model="mobileDetails" fullscreen transition="dialog-bottom-transition">
    <v-card v-if="selected" color="surface">
      <ContactDetails
        :contact="selected" closable
        @edit="editContact(selected)" @delete="deleteContact(selected)" @close="mobileDetails = false"
      />
    </v-card>
  </v-dialog>

  <ContactDialog v-model="editorOpen" :contact-id="editId" :book-id="bookFilter" :category="categoryFilter" @saved="onSaved" />
  <v-dialog v-model="renameOpen" max-width="400">
    <v-card :title="$t('contactsView.renameCategory')" color="surface-container-high">
      <v-card-text>
        <v-text-field
          v-model="renameTo" :label="$t('common.name')" autofocus hide-details maxlength="100" @keydown.enter="renameCategory"
        />
        <div class="text-body-small text-medium-emphasis mt-3">
          {{ $t('contactsView.renameHint') }}
        </div>
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <v-btn @click="renameOpen = false">{{ $t('common.cancel') }}</v-btn>
        <v-btn color="primary" :loading="renaming" :disabled="!renameTo.trim()" @click="renameCategory">{{ $t('contactsView.rename') }}</v-btn>
      </v-card-actions>
    </v-card>
  </v-dialog>
  <AddressBookDialog v-model="bookDialog" :book="editBook" />
  <DavSetupDialog v-model="davOpen" />
  <ConfirmDialog ref="confirmRef" />
</template>

<style scoped>
.main { height: 100dvh; background: rgb(var(--v-theme-surface-container-low)); }
.view-surface {
  position: relative; height: 100%; background: rgb(var(--v-theme-surface)); border-top-left-radius: 24px; overflow: hidden;
}
.create-btn { text-transform: none; letter-spacing: .1px; font-weight: 500; }
.search { max-width: 560px; }
.brand { min-width: 200px; }
.list-pane { display: flex; flex-direction: column; flex: 1 1 auto; min-width: 0; height: 100%; }
.list-pane.narrow { flex: 0 0 380px; max-width: 380px; border-right: 1px solid rgba(var(--v-border-color), var(--v-border-opacity)); }
.rows { flex: 1 1 auto; padding-bottom: 88px; }
.letter { padding: 12px 24px 4px; color: rgb(var(--v-theme-primary)); }
.contact-row { margin-block: 1px; }
.detail-pane { flex: 1 1 auto; min-width: 0; height: 100%; overflow-y: auto; }
.empty { max-width: 520px; margin-inline: auto; }
.book-item .book-menu { opacity: 0; transition: opacity .15s; }
.book-item:hover .book-menu, .book-item:focus-within .book-menu { opacity: 1; }
.book-item:hover .count { display: none; }
@media (hover: none) { .book-item .book-menu { opacity: 1; } .book-item .count { display: none; } }
.drop-hint {
  position: absolute; inset: 12px; border: 2px dashed rgb(var(--v-theme-primary)); border-radius: 20px;
  background: rgba(var(--v-theme-primary-container), .85); color: rgb(var(--v-theme-on-primary-container));
  display: flex; flex-direction: column; align-items: center; justify-content: center; pointer-events: none;
}
.fab { position: fixed; right: 16px; bottom: 16px; z-index: 5; }
@media (max-width: 959px) { .view-surface { border-radius: 0; } }
</style>
