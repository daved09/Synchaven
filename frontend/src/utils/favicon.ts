// Favicon with today's day (like Google Calendar). Same motif as public/favicon.svg,
// which remains as a static fallback (e.g. for bookmarks without JavaScript).
function faviconSvg(day: number): string {
  return (
    '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 64 64">' +
    '<rect x="6" y="10" width="52" height="48" rx="12" fill="#6750A4"/>' +
    '<rect x="6" y="10" width="52" height="16" rx="8" fill="#4F378B"/>' +
    '<rect x="18" y="4" width="6" height="12" rx="3" fill="#21005D"/>' +
    '<rect x="40" y="4" width="6" height="12" rx="3" fill="#21005D"/>' +
    '<text x="32" y="50" font-family="Roboto,Arial,sans-serif" font-size="24" font-weight="700" ' +
    `fill="#fff" text-anchor="middle">${day}</text></svg>`
  )
}

function applyFavicon() {
  let link = document.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (!link) {
    link = document.createElement('link')
    link.rel = 'icon'
    document.head.appendChild(link)
  }
  link.type = 'image/svg+xml'
  link.href = `data:image/svg+xml,${encodeURIComponent(faviconSvg(new Date().getDate()))}`
}

/** Sets the favicon to today and updates it after midnight or when returning to the tab. */
export function startDynamicFavicon() {
  applyFavicon()
  const scheduleMidnight = () => {
    const now = new Date()
    const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 5)
    setTimeout(() => {
      applyFavicon()
      scheduleMidnight()
    }, next.getTime() - now.getTime())
  }
  scheduleMidnight()
  // Timers are delayed in background tabs or during sleep – catch up when returning.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') applyFavicon()
  })
}
