// Gemeinsame Zeiger-Logik zum Verschieben von Terminen per Drag & Drop.

const THRESHOLD = 5

export interface DragHandlers {
  /** Wird einmal aufgerufen, sobald die Bewegung die Schwelle überschreitet */
  start?: () => void
  move: (e: PointerEvent) => void
  drop: (e: PointerEvent) => void
  cancel: () => void
}

/** Verhindert, dass der Klick nach dem Loslassen einen Dialog öffnet oder einen Termin anlegt. */
function swallowNextClick() {
  const stop = (e: Event) => {
    e.stopPropagation()
    e.preventDefault()
  }
  window.addEventListener('click', stop, { capture: true, once: true })
  setTimeout(() => window.removeEventListener('click', stop, { capture: true }), 0)
}

/**
 * Beobachtet einen Zeiger ab `pointerdown`. Erst nach einer kleinen Bewegung wird daraus ein Drag,
 * ein einfacher Klick bleibt ein Klick. Escape bricht ab. Touch wird ignoriert (Scrollen/Wischen).
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

/** Index des Elements, dessen horizontaler Bereich `x` enthält (außerhalb: nächster Rand). */
export function indexAtX(els: HTMLElement[], x: number): number {
  for (let i = 0; i < els.length; i++) {
    const r = els[i].getBoundingClientRect()
    if (x < r.right || i === els.length - 1) return i
  }
  return 0
}
/** Index des Elements, dessen vertikaler Bereich `y` enthält (außerhalb: nächster Rand). */
export function indexAtY(els: HTMLElement[], y: number): number {
  for (let i = 0; i < els.length; i++) {
    const r = els[i].getBoundingClientRect()
    if (y < r.bottom || i === els.length - 1) return i
  }
  return 0
}
