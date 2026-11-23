import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { Task, TaskInput, TaskList, TaskListInput, ViewEvent } from '@/api/types'
import { addDays, addMinutes, allDayToLocal, localToAllDay } from '@/utils/date'

/** Präfix der IDs, unter denen Aufgaben im Kalender erscheinen (getrennt von Termin-IDs) */
export const TASK_PREFIX = 'task:'
/** Höhe einer Aufgabe mit Uhrzeit im Zeitraster */
const TASK_MINUTES = 30

/** Fälligkeit als lokales Datum (ganztägig: Mitternacht lokal) */
export function taskDueDate(t: Task): Date | null {
  if (!t.due) return null
  return t.all_day ? allDayToLocal(t.due) : new Date(t.due)
}

/** Eingabe zum Speichern einer vorhandenen Aufgabe mit geänderten Feldern */
export function taskInput(t: Task, patch: Partial<TaskInput> = {}): TaskInput {
  return { list_id: t.list_id, title: t.title, notes: t.notes, due: t.due, all_day: t.all_day, ...patch }
}

function toViewEvent(t: Task, color: string): ViewEvent | null {
  const startDate = taskDueDate(t)
  if (!startDate) return null
  const endDate = t.all_day ? addDays(startDate, 1) : addMinutes(startDate, TASK_MINUTES)
  return {
    id: TASK_PREFIX + t.id,
    event_id: t.id,
    calendar_id: t.list_id,
    title: t.title,
    description: t.notes,
    location: '',
    start: t.due!,
    end: t.all_day ? localToAllDay(endDate) : endDate.toISOString(),
    all_day: t.all_day,
    timezone: '',
    recurring: false,
    rrule: null,
    contact_id: null,
    read_only: false,
    reminders: null,
    startDate,
    endDate,
    color,
    task: t,
  }
}

function byDue(a: Task, b: Task) {
  if (!a.due !== !b.due) return a.due ? -1 : 1
  return (a.due ?? '').localeCompare(b.due ?? '') || a.created_at.localeCompare(b.created_at)
}

export const useTasksStore = defineStore('tasks', () => {
  const lists = ref<TaskList[]>([])
  const tasks = ref<Task[]>([])
  const listById = computed(() => new Map(lists.value.map((l) => [l.id, l])))
  const visibleListIds = computed(() => new Set(lists.value.filter((l) => l.visible).map((l) => l.id)))

  /** Aufgaben mit Fälligkeit aus eingeblendeten Listen – für die Kalenderansichten */
  const calendarEvents = computed<ViewEvent[]>(() =>
    tasks.value
      .filter((t) => visibleListIds.value.has(t.list_id))
      .map((t) => toViewEvent(t, listById.value.get(t.list_id)?.color ?? '#1A73E8'))
      .filter((e): e is ViewEvent => e !== null),
  )

  function openCount(listId: string) {
    return tasks.value.filter((t) => t.list_id === listId && !t.completed).length
  }
  /** Offene Aufgaben einer Liste nach Fälligkeit, ohne Datum zuletzt */
  function openOf(listId: string) {
    return tasks.value.filter((t) => t.list_id === listId && !t.completed).sort(byDue)
  }
  /** Erledigte Aufgaben einer Liste, zuletzt erledigte zuerst */
  function completedOf(listId: string) {
    return tasks.value
      .filter((t) => t.list_id === listId && t.completed)
      .sort((a, b) => (b.completed_at ?? '').localeCompare(a.completed_at ?? ''))
  }

  async function load() {
    const [l, t] = await Promise.all([api.taskLists(), api.tasks()])
    lists.value = l
    tasks.value = t
  }
  function put(t: Task) {
    tasks.value = tasks.value.some((x) => x.id === t.id)
      ? tasks.value.map((x) => (x.id === t.id ? t : x))
      : [...tasks.value, t]
    return t
  }

  // --- Listen ---
  async function createList(data: TaskListInput) {
    const l = await api.createTaskList(data)
    lists.value.push(l)
    return l
  }
  async function updateList(id: string, data: TaskListInput) {
    const l = await api.updateTaskList(id, data)
    lists.value = lists.value.map((x) => (x.id === id ? l : x))
    return l
  }
  async function removeList(id: string) {
    await api.deleteTaskList(id)
    lists.value = lists.value.filter((x) => x.id !== id)
    tasks.value = tasks.value.filter((t) => t.list_id !== id)
  }
  async function toggleListVisible(list: TaskList) {
    list.visible = !list.visible // optimistisch
    try {
      await updateList(list.id, { name: list.name, color: list.color, visible: list.visible })
    } catch (e) {
      list.visible = !list.visible
      throw e
    }
  }
  async function clearCompleted(listId: string) {
    const r = await api.clearCompletedTasks(listId)
    tasks.value = tasks.value.filter((t) => t.list_id !== listId || !t.completed)
    return r.deleted
  }

  // --- Aufgaben ---
  async function create(input: TaskInput) {
    return put(await api.createTask(input))
  }
  async function update(id: string, input: TaskInput) {
    return put(await api.updateTask(id, input))
  }
  async function remove(id: string) {
    await api.deleteTask(id)
    tasks.value = tasks.value.filter((t) => t.id !== id)
  }
  /** Abhaken oder wieder öffnen – sofort sichtbar, bei einem Fehler zurück */
  async function setCompleted(task: Task, completed: boolean) {
    const before = tasks.value.find((t) => t.id === task.id) ?? task
    put({ ...before, completed, completed_at: completed ? new Date().toISOString() : null })
    try {
      return put(await api.setTaskCompleted(task.id, completed))
    } catch (e) {
      put(before)
      throw e
    }
  }
  /** Fälligkeit verschieben (Drag & Drop im Kalender); die Uhrzeit wandert mit */
  async function move(task: Task, newStart: Date) {
    const due = task.all_day ? localToAllDay(newStart) : newStart.toISOString()
    const before = tasks.value.find((t) => t.id === task.id) ?? task
    put({ ...before, due })
    try {
      return put(await api.updateTask(task.id, taskInput(before, { due })))
    } catch (e) {
      put(before)
      throw e
    }
  }

  function reset() {
    lists.value = []
    tasks.value = []
  }
  return {
    lists, tasks, listById, visibleListIds, calendarEvents, openCount, openOf, completedOf,
    load, createList, updateList, removeList, toggleListVisible, clearCompleted,
    create, update, remove, setCompleted, move, reset,
  }
})

/** Symbol einer Aufgabe im Kalender: leerer Kreis oder Haken */
export function taskIcon(t: Task): string {
  return t.completed ? 'mdi-check-circle' : 'mdi-checkbox-blank-circle-outline'
}
