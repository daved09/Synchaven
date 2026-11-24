/* Synchaven Service Worker: zeigt Erinnerungen an Termine und Aufgaben an, die der Server per Web Push schickt. */

self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()))

const LOCALE = 'de-DE'
const pad = (n) => String(n).padStart(2, '0')
const dayKey = (d) => `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`

/** Ganztägige Termine kommen als Mitternacht UTC und werden als lokales Datum gelesen. */
function localDate(iso, allDay) {
  const d = new Date(iso)
  return allDay ? new Date(d.getUTCFullYear(), d.getUTCMonth(), d.getUTCDate()) : d
}

function dayLabel(d) {
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  const day = new Date(d)
  day.setHours(0, 0, 0, 0)
  const diff = Math.round((day - today) / 86400000)
  if (diff === 0) return 'Heute'
  if (diff === 1) return 'Morgen'
  if (diff === -1) return 'Gestern'
  return d.toLocaleDateString(LOCALE, { weekday: 'short', day: 'numeric', month: 'short' })
}

const time = (d) => d.toLocaleTimeString(LOCALE, { hour: '2-digit', minute: '2-digit' })

function describe(p) {
  const start = localDate(p.start, p.all_day)
  let when
  if (p.all_day) {
    when = `${dayLabel(start)} (ganztägig)`
  } else {
    const end = new Date(p.end)
    const sameDay = dayKey(start) === dayKey(end)
    when = sameDay
      ? `${dayLabel(start)}, ${time(start)}–${time(end)}`
      : `${dayLabel(start)}, ${time(start)} – ${dayLabel(end)}, ${time(end)}`
  }
  return p.location ? `${when}\n${p.location}` : when
}

self.addEventListener('push', (event) => {
  let p = {}
  try {
    p = event.data ? event.data.json() : {}
  } catch {
    p = { title: 'Synchaven', body: event.data ? event.data.text() : '' }
  }
  const title = p.title || 'Synchaven'
  let options
  if (p.type === 'reminder') {
    const start = localDate(p.start, p.all_day)
    options = {
      body: describe(p),
      tag: p.tag,
      timestamp: start.getTime(),
      data: { url: `/?view=day&date=${dayKey(start)}` },
    }
  } else if (p.type === 'task') {
    const due = localDate(p.due, p.all_day)
    const when = p.all_day ? `Fällig: ${dayLabel(due)}` : `Fällig: ${dayLabel(due)}, ${time(due)}`
    options = {
      body: p.notes ? `${when}\n${p.notes}` : when,
      tag: p.tag,
      timestamp: due.getTime(),
      data: { url: `/?view=day&date=${dayKey(due)}` },
    }
  } else {
    options = { body: p.body || '', tag: p.tag || 'synchaven', data: { url: '/' } }
  }
  options.icon = '/favicon.svg'
  options.badge = '/favicon.svg'
  event.waitUntil(self.registration.showNotification(title, options))
})

self.addEventListener('notificationclick', (event) => {
  event.notification.close()
  const url = new URL((event.notification.data && event.notification.data.url) || '/', self.location.origin).href
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
      const client = windows.find((c) => new URL(c.url).origin === self.location.origin)
      if (client) {
        await client.focus()
        if ('navigate' in client) await client.navigate(url).catch(() => {})
        return
      }
      await self.clients.openWindow(url)
    })(),
  )
})
