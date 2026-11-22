<script setup lang="ts">
import { ref } from 'vue'

const open = ref(false)
const title = ref('')
const text = ref('')
const confirmText = ref('OK')
const confirmColor = ref('error')
let resolver: ((v: boolean) => void) | null = null

function ask(t: string, body: string, ok = 'Löschen', color = 'error'): Promise<boolean> {
  title.value = t
  text.value = body
  confirmText.value = ok
  confirmColor.value = color
  open.value = true
  return new Promise((resolve) => (resolver = resolve))
}
function close(result: boolean) {
  open.value = false
  resolver?.(result)
  resolver = null
}
defineExpose({ ask })
</script>

<template>
  <v-dialog v-model="open" max-width="400" @update:model-value="(v) => !v && close(false)">
    <v-card :title="title" :text="text" color="surface-container-high">
      <template #actions>
        <v-spacer />
        <v-btn @click="close(false)">Abbrechen</v-btn>
        <v-btn :color="confirmColor" @click="close(true)">{{ confirmText }}</v-btn>
      </template>
    </v-card>
  </v-dialog>
</template>
