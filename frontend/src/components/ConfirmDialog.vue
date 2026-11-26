<script setup lang="ts">
import { useConfirmStore } from '@/stores/confirm'

const confirm = useConfirmStore()
</script>

<template>
  <v-dialog :model-value="confirm.open" max-width="400" @update:model-value="(v) => !v && confirm.close(false)">
    <v-card :title="confirm.title" :text="confirm.text" color="surface-container-high">
      <template #actions>
        <v-spacer />
        <v-btn @click="confirm.close(false)">{{ $t('common.cancel') }}</v-btn>
        <v-btn :color="confirm.confirmColor" @click="confirm.close(true)">{{ confirm.confirmText }}</v-btn>
      </template>
    </v-card>
  </v-dialog>
</template>
