import { t as tr } from '@/i18n'
import type { Task } from '@/api/types'
import { useConfirmStore } from '@/stores/confirm'
import { useNotifyStore } from '@/stores/notify'
import { useTasksStore } from '@/stores/tasks'

/** Task actions with confirmation and feedback */
export function useTaskActions() {
  const tasks = useTasksStore()
  const notify = useNotifyStore()
  const confirm = useConfirmStore()

  async function toggle(t: Task) {
    try {
      const done = !t.completed
      await tasks.setCompleted(t, done)
      notify.info(done ? tr('calendarView.taskDone') : tr('calendarView.taskReopened'))
    } catch (e) {
      notify.fail(e)
    }
  }
  async function move(t: Task, due: Date) {
    try {
      await tasks.move(t, due)
      notify.info(tr('calendarView.taskMoved'))
    } catch (e) {
      notify.fail(e)
    }
  }
  /** Asks first; resolves to true once the task (with its subtasks) is deleted */
  async function remove(t: Task): Promise<boolean> {
    const subs = tasks.subtasksOf(t.id).length
    const text = subs === 0 ? tr('common.deletesNamed', { name: t.title })
      : tr('calendarView.deleteTaskWithSubtasks', { name: t.title, n: subs }, subs)
    if (!(await confirm.ask(tr('calendarView.deleteTaskTitle'), text))) return false
    try {
      await tasks.remove(t.id)
      notify.info(tr('calendarView.taskDeleted'))
      return true
    } catch (e) {
      notify.fail(e)
      return false
    }
  }

  return { toggle, move, remove }
}
