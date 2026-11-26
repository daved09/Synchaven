import { LOCALE, t as tr } from '@/i18n'
import type { ViewEvent } from '@/api/types'
import { useConfirmStore } from '@/stores/confirm'
import { useEventsStore } from '@/stores/events'
import { useNotifyStore } from '@/stores/notify'

/** Event actions with confirmation and feedback */
export function useEventActions() {
  const events = useEventsStore()
  const notify = useNotifyStore()
  const confirm = useConfirmStore()

  /** Asks first; resolves to true once the occurrence (`one`) or the whole event (`all`) is deleted */
  async function remove(ev: ViewEvent, scope: 'one' | 'all'): Promise<boolean> {
    const text = scope === 'one'
      ? tr('calendarView.deleteOccurrence', { date: ev.startDate.toLocaleDateString(LOCALE) })
      : ev.recurring ? tr('calendarView.deleteSeries') : tr('common.deletesNamed', { name: ev.title })
    if (!(await confirm.ask(tr('calendarView.deleteEventTitle'), text))) return false
    try {
      if (scope === 'one') await events.removeOccurrence(ev.event_id, ev.start)
      else await events.remove(ev.event_id)
      notify.info(tr('calendarView.eventDeleted'))
      return true
    } catch (e) {
      notify.fail(e)
      return false
    }
  }
  /** Moves an event; a recurring one only after asking, since only this occurrence moves */
  async function move(ev: ViewEvent, newStart: Date) {
    if (ev.recurring && !(await confirm.ask(
      tr('calendarView.moveOccurrenceTitle'),
      tr('calendarView.moveOccurrenceText', { date: ev.startDate.toLocaleDateString(LOCALE) }),
      tr('calendarView.move'),
      'primary',
    ))) return
    try {
      await events.move(ev, newStart)
      notify.info(tr('calendarView.eventMoved'))
    } catch (e) {
      notify.fail(e)
    }
  }

  return { remove, move }
}
