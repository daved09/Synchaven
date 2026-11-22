<script setup lang="ts">
import { computed } from 'vue'
import { fmt } from '@/utils/date'
import { WEEKDAYS, WEEKDAY_LABELS, jsDayToCode, weekdayOrdinal, type RecurrenceForm } from '@/utils/rrule'

const form = defineModel<RecurrenceForm>({ required: true })
const props = defineProps<{ start: Date }>()

const freqItems = [
  { title: 'Wiederholt sich nicht', value: 'NONE' },
  { title: 'Täglich', value: 'DAILY' },
  { title: 'Wöchentlich', value: 'WEEKLY' },
  { title: 'Monatlich', value: 'MONTHLY' },
  { title: 'Jährlich', value: 'YEARLY' },
  { title: 'Benutzerdefiniert (RRULE)', value: 'CUSTOM' },
]
const unit = computed(() => {
  const n = form.value.interval
  return { DAILY: n === 1 ? 'Tag' : 'Tage', WEEKLY: n === 1 ? 'Woche' : 'Wochen', MONTHLY: n === 1 ? 'Monat' : 'Monate', YEARLY: n === 1 ? 'Jahr' : 'Jahre' }[
    form.value.freq as 'DAILY'
  ] ?? ''
})
const short: Record<string, string> = { MO: 'Mo', TU: 'Di', WE: 'Mi', TH: 'Do', FR: 'Fr', SA: 'Sa', SU: 'So' }
const ordinal = computed(() => {
  const n = weekdayOrdinal(props.start)
  return `${n === -1 ? 'letzten' : `${n}.`} ${WEEKDAY_LABELS[jsDayToCode(props.start)]}`
})
const simple = computed(() => !['NONE', 'CUSTOM'].includes(form.value.freq))
</script>

<template>
  <div>
    <v-select v-model="form.freq" :items="freqItems" label="Wiederholung" prepend-inner-icon="mdi-repeat" hide-details class="mb-3" />

    <template v-if="simple">
      <div class="d-flex align-center ga-3 mb-3">
        <span class="text-body-large">Alle</span>
        <v-text-field
          v-model.number="form.interval" type="number" min="1" max="999" density="compact"
          hide-details style="max-width: 96px"
        />
        <span class="text-body-large">{{ unit }}</span>
      </div>

      <div v-if="form.freq === 'WEEKLY'" class="mb-3">
        <div class="text-label-large mb-1">An folgenden Tagen</div>
        <v-chip-group v-model="form.byday" multiple selected-class="text-primary" column>
          <v-chip v-for="d in WEEKDAYS" :key="d" :value="d" filter variant="outlined" rounded="lg" :text="short[d]" />
        </v-chip-group>
      </div>

      <v-radio-group v-if="form.freq === 'MONTHLY'" v-model="form.monthlyMode" hide-details density="compact" class="mb-3">
        <v-radio value="day" :label="`Monatlich am ${start.getDate()}.`" />
        <v-radio value="weekday" :label="`Monatlich am ${ordinal}`" />
      </v-radio-group>

      <div class="text-label-large mb-1">Endet</div>
      <v-radio-group v-model="form.endMode" hide-details density="compact">
        <v-radio value="never" label="Nie" />
        <v-radio value="until">
          <template #label>
            <div class="d-flex align-center ga-3">
              <span>Am</span>
              <v-text-field
                v-model="form.until" type="date" density="compact" hide-details style="min-width: 170px"
                :disabled="form.endMode !== 'until'" @click.stop="form.endMode = 'until'"
              />
            </div>
          </template>
        </v-radio>
        <v-radio value="count">
          <template #label>
            <div class="d-flex align-center ga-3">
              <span>Nach</span>
              <v-text-field
                v-model.number="form.count" type="number" min="1" density="compact" hide-details style="max-width: 96px"
                :disabled="form.endMode !== 'count'"
              />
              <span>Terminen</span>
            </div>
          </template>
        </v-radio>
      </v-radio-group>
      <div v-if="form.freq === 'YEARLY'" class="text-body-small text-medium-emphasis mt-2">
        Jedes Jahr am {{ fmt(start, { day: 'numeric', month: 'long' }) }}
      </div>
    </template>

    <v-text-field
      v-if="form.freq === 'CUSTOM'"
      v-model="form.raw"
      label="RRULE nach RFC 5545"
      placeholder="FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1"
      hint="z. B. letzter Werktag im Monat – siehe RFC 5545, Abschnitt 3.3.10"
      persistent-hint
    />
  </div>
</template>
