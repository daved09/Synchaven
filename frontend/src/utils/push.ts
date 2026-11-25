/**
 * Web Push: the server sends reminders to the service worker (`/sw.js`), which shows them as
 * system notifications – even if no Synchaven tab is open.
 */
import { api } from '@/api/client'
import { browserTimeZone } from './date'

export type PushState =
  /** Browser doesn't support Web Push */
  | 'unsupported'
  /** Page is not served via HTTPS (or localhost) */
  | 'insecure'
  /** Notifications blocked for this page in the browser */
  | 'denied'
  | 'off'
  | 'on'

const supported = () => 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window

/** Registers the service worker (on app start). */
export async function registerServiceWorker(): Promise<ServiceWorkerRegistration | null> {
  if (!supported() || !window.isSecureContext) return null
  try {
    return await navigator.serviceWorker.register('/sw.js')
  } catch (e) {
    console.warn('Could not register service worker', e)
    return null
  }
}

async function registration() {
  return (await navigator.serviceWorker.getRegistration('/')) ?? (await registerServiceWorker())
}

async function currentSubscription(): Promise<PushSubscription | null> {
  const reg = await registration()
  return reg ? reg.pushManager.getSubscription() : null
}

export async function pushState(): Promise<PushState> {
  if (!window.isSecureContext) return 'insecure'
  if (!supported()) return 'unsupported'
  if (Notification.permission === 'denied') return 'denied'
  if (Notification.permission !== 'granted') return 'off'
  return (await currentSubscription()) ? 'on' : 'off'
}

function keyBytes(base64url: string): Uint8Array<ArrayBuffer> {
  const b64 = base64url.replace(/-/g, '+').replace(/_/g, '/') + '='.repeat((4 - (base64url.length % 4)) % 4)
  const raw = atob(b64)
  const out = new Uint8Array(raw.length)
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

const sameBytes = (a: Uint8Array, b: Uint8Array) => a.length === b.length && a.every((v, i) => v === b[i])

async function send(sub: PushSubscription) {
  const json = sub.toJSON()
  await api.pushSubscribe({
    endpoint: sub.endpoint,
    keys: { p256dh: json.keys?.p256dh ?? '', auth: json.keys?.auth ?? '' },
    timezone: browserTimeZone(),
    user_agent: navigator.userAgent,
  })
}

/** Asks for permission and subscribes the browser on the server. */
export async function enablePush(): Promise<PushState> {
  const state = await pushState()
  if (state === 'insecure' || state === 'unsupported' || state === 'denied') return state
  if ((await Notification.requestPermission()) !== 'granted') return pushState()
  const reg = await registration()
  if (!reg) return 'unsupported'
  await navigator.serviceWorker.ready
  const { public_key } = await api.pushPublicKey()
  let sub = await reg.pushManager.getSubscription()
  // Replace a subscription with a different server key (e.g. after a reinstall)
  const serverKey = keyBytes(public_key)
  const current = sub?.options.applicationServerKey
  if (sub && current && !sameBytes(new Uint8Array(current), serverKey)) {
    await sub.unsubscribe()
    sub = null
  }
  sub ??= await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: serverKey })
  await send(sub)
  return 'on'
}

/** Unsubscribe on the server and in the browser (also when signing out of the account). */
export async function disablePush(): Promise<void> {
  if (!supported() || !window.isSecureContext) return
  const sub = await currentSubscription().catch(() => null)
  if (!sub) return
  await api.pushUnsubscribe(sub.endpoint).catch(() => {})
  await sub.unsubscribe()
}

/** After sign-in: assign an existing subscription to the current user and update the time zone. */
export async function refreshPush(): Promise<void> {
  if ((await pushState()) !== 'on') return
  const sub = await currentSubscription()
  if (sub) await send(sub)
}

export async function testPush(): Promise<void> {
  const sub = await currentSubscription()
  await api.pushTest(sub?.endpoint)
}
