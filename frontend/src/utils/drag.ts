// Shared pointer logic for moving events via drag & drop.

const THRESHOLD = 5

export interface DragHandlers {
  /** Called once as soon as the movement exceeds the threshold */
  start?: () => void
  move: (e: PointerEvent) => void
  drop: (e: PointerEvent) => void
  cancel: () => void
}

/** Prevents the click after releasing from opening a dialog or creating an event. */
function swallowNextClick() {
  const stop = (e: Event) => {
    e.stopPropagation()
    e.preventDefault()
  }
  window.addEventListener('click', stop, { capture: true, once: true })
  setTimeout(() => window.removeEventListener('click', stop, { capture: true }), 0)
}

/**
 * Tracks a pointer from `pointerdown`. Only after a small movement does it become a drag;
 * a simple click stays a click. Escape cancels. Touch is ignored (scrolling/swiping).
 */
export function trackDrag(down: PointerEvent, h: DragHandlers) {
  if (down.button !== 0 || down.pointerType === 'touch') return
  const x0 = down.clientX
  const y0 = down.clientY
  let active = false

  const onMove = (e: PointerEvent) => {
    if (e.pointerId !== down.pointerId) return
    if (!active) {
      if (Math.hypot(e.clientX - x0, e.clientY - y0) < THRESHOLD) return
      active = true
      document.body.classList.add('event-dragging')
      window.getSelection()?.removeAllRanges()
      h.start?.()
    }
    e.preventDefault()
    h.move(e)
  }
  const finish = (e: PointerEvent | null) => {
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    window.removeEventListener('pointercancel', onCancel)
    window.removeEventListener('keydown', onKey, true)
    document.body.classList.remove('event-dragging')
    if (!active) return
    swallowNextClick()
    if (e) h.drop(e)
    else h.cancel()
  }
  const onUp = (e: PointerEvent) => e.pointerId === down.pointerId && finish(e)
  const onCancel = (e: PointerEvent) => e.pointerId === down.pointerId && finish(null)
  const onKey = (e: KeyboardEvent) => {
    if (e.key !== 'Escape') return
    e.stopPropagation()
    finish(null)
  }

  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
  window.addEventListener('pointercancel', onCancel)
  window.addEventListener('keydown', onKey, true)
}

/** Index of the element whose horizontal range contains `x` (outside: nearest edge). */
export function indexAtX(els: HTMLElement[], x: number): number {
  for (let i = 0; i < els.length; i++) {
    const r = els[i].getBoundingClientRect()
    if (x < r.right || i === els.length - 1) return i
  }
  return 0
}
/** Index of the element whose vertical range contains `y` (outside: nearest edge). */
export function indexAtY(els: HTMLElement[], y: number): number {
  for (let i = 0; i < els.length; i++) {
    const r = els[i].getBoundingClientRect()
    if (y < r.bottom || i === els.length - 1) return i
  }
  return 0
}
