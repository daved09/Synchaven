import { onBeforeUnmount, onMounted } from 'vue'

/**
 * Single-key shortcuts for a view, keyed by `KeyboardEvent.key` in lower case.
 * A handler returning `false` lets the key through.
 * Ignored with modifier keys, while typing, inside overlays and while any dialog is open.
 */
export function useShortcuts(handlers: Record<string, () => unknown>) {
  function onKey(e: KeyboardEvent) {
    if (e.ctrlKey || e.metaKey || e.altKey) return
    if ((e.target as HTMLElement).closest('input, textarea, select, [contenteditable="true"], .v-overlay')) return
    if (document.querySelector('.v-dialog.v-overlay--active')) return
    const fn = handlers[e.key.toLowerCase()]
    if (fn && fn() !== false) e.preventDefault()
  }
  onMounted(() => window.addEventListener('keydown', onKey))
  onBeforeUnmount(() => window.removeEventListener('keydown', onKey))
}
