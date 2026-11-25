<script setup lang="ts">
import { t } from '@/i18n'
import { computed } from 'vue'
import { fmt } from '@/utils/date'
import { WEEKDAYS, WEEKDAY_LABELS, jsDayToCode, ordinalText, weekdayOrdinal, type RecurrenceForm } from '@/utils/rrule'

const form = defineModel<RecurrenceForm>({ required: true })
const props = defineProps<{ start: Date }>()

const freqItems = (['NONE', 'DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY', 'CUSTOM'] as const)
  .map((value) => ({ title: t(`recurrence.freq.${value}`), value }))
const unit = computed(() => {
  const freq = form.value.freq
  return ['DAILY', 'WEEKLY', 'MONTHLY', 'YEARLY'].includes(freq) ? t(`recurrence.unit.${freq}`, form.value.interval) : ''
})
const short = Object.fromEntries(
  WEEKDAYS.map((d, i) => [d, fmt(new Date(2026, 0, 5 + i), { weekday: 'short' }).replace(/\.$/, '')]),
)
const monthlyOnDay = computed(() => t('recurrence.monthly', { on: t('recurrence.onDay', { day: props.start.getDate() }) }, 1))
const monthlyOnWeekday = computed(() => t('recurrence.monthly', {
  on: t('recurrence.onWeekday', {
    ordinal: ordinalText(weekdayOrdinal(props.start)),
    weekday: WEEKDAY_LABELS[jsDayToCode(props.start)],
  }),
}, 1))
const simple = computed(() => !['NONE', 'CUSTOM'].includes(form.value.freq))
</script>

<template>
  <div>
    <v-select v-model="form.freq" :items="freqItems" :label="$t('recurrence.label')" prepend-inner-icon="mdi-repeat" hide-details class="mb-3" />

    <template v-if="simple">
      <div class="d-flex align-center ga-3 mb-3">
        <span class="text-body-large">{{ $t('recurrence.every') }}</span>
        <v-text-field
          v-model.number="form.interval" type="number" min="1" max="999" density="compact"
          hide-details style="max-width: 96px"
        />
        <span class="text-body-large">{{ unit }}</span>
      </div>

      <div v-if="form.freq === 'WEEKLY'" class="mb-3">
        <div class="text-label-large mb-1">{{ $t('recurrence.onDays') }}</div>
        <v-chip-group v-model="form.byday" multiple selected-class="text-primary" column>
          <v-chip v-for="d in WEEKDAYS" :key="d" :value="d" filter variant="outlined" rounded="lg" :text="short[d]" />
        </v-chip-group>
      </div>

      <v-radio-group v-if="form.freq === 'MONTHLY'" v-model="form.monthlyMode" hide-details density="compact" class="mb-3">
        <v-radio value="day" :label="monthlyOnDay" />
        <v-radio value="weekday" :label="monthlyOnWeekday" />
      </v-radio-group>

      <div class="text-label-large mb-1">{{ $t('recurrence.ends') }}</div>
      <v-radio-group v-model="form.endMode" hide-details density="compact">
        <v-radio value="never" :label="$t('recurrence.never')" />
        <v-radio value="until">
          <template #label>
            <div class="d-flex align-center ga-3">
              <span>{{ $t('recurrence.endOn') }}</span>
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
              <span>{{ $t('recurrence.endAfter') }}</span>
              <v-text-field
                v-model.number="form.count" type="number" min="1" density="compact" hide-details style="max-width: 96px"
                :disabled="form.endMode !== 'count'"
              />
              <span>{{ $t('recurrence.occurrences') }}</span>
            </div>
          </template>
        </v-radio>
      </v-radio-group>
      <div v-if="form.freq === 'YEARLY'" class="text-body-small text-medium-emphasis mt-2">
        {{ $t('recurrence.everyYearOn', { date: fmt(start, { day: 'numeric', month: 'long' }) }) }}
      </div>
    </template>

    <v-text-field
      v-if="form.freq === 'CUSTOM'"
      v-model="form.raw"
      :label="$t('recurrence.rruleLabel')"
      placeholder="FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1"
      :hint="$t('recurrence.rruleHint')"
      persistent-hint
    />
  </div>
</template>
