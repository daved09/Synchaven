<script setup lang="ts">
import { computed } from 'vue'
import type { TypedValue } from '@/api/types'
import type { TypeOption } from '@/utils/contact'

const items = defineModel<TypedValue[]>({ required: true })
const props = defineProps<{
  label: string
  icon: string
  types: TypeOption[]
  inputType: 'tel' | 'email' | 'url'
  addText: string
  defaultType: string
}>()

/** Offer custom labels (e.g. "Zentrale") in addition to the standard types. */
const options = computed(() => {
  const custom = items.value
    .map((i) => i.type)
    .filter((t) => t && !props.types.some((o) => o.value === t))
    .map((t) => ({ title: t, value: t }))
  return [...props.types, ...custom]
})

function add() {
  items.value = [...items.value, { type: props.defaultType, value: '' }]
}
function remove(i: number) {
  items.value = items.value.filter((_, j) => j !== i)
}
function setType(i: number, t: string | null) {
  items.value[i].type = (t ?? '').trim()
}
</script>

<template>
  <div class="mb-2">
    <div v-for="(item, i) in items" :key="i" class="d-flex ga-2 align-start">
      <v-icon :icon="icon" class="mt-4 field-icon" :class="{ invisible: i > 0 }" />
      <v-text-field
        v-model="item.value" :label="label" :type="inputType" class="flex-1-1"
        :autocomplete="inputType === 'email' ? 'email' : inputType === 'tel' ? 'tel' : 'url'"
      />
      <v-combobox
        :model-value="item.type" :items="options" item-title="title" item-value="value" :return-object="false"
        :label="$t('contact.type')" class="type-field" @update:model-value="(t: string | null) => setType(i, t)"
      />
      <v-btn icon="mdi-close" variant="text" size="small" class="mt-2" :title="$t('common.removeNamed', { name: label })" @click="remove(i)" />
    </div>
    <v-btn variant="text" prepend-icon="mdi-plus" class="ml-8" size="small" @click="add">{{ addText }}</v-btn>
  </div>
</template>

<style scoped>
.type-field { flex: 0 0 170px; max-width: 170px; }
.field-icon { opacity: .7; }
.invisible { visibility: hidden; }
@media (max-width: 599px) { .type-field { flex-basis: 115px; max-width: 115px; } }
</style>
