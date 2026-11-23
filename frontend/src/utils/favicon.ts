// Favicon mit dem heutigen Tag (wie Google Kalender). Gleiches Motiv wie public/favicon.svg,
// das als statischer Fallback (z. B. für Lesezeichen ohne JavaScript) bestehen bleibt.
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

/** Setzt das Favicon auf heute und aktualisiert es nach Mitternacht bzw. beim Zurückkehren zum Tab. */
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
  // Timer laufen in Hintergrund-Tabs bzw. im Ruhezustand verzögert – beim Zurückkehren nachziehen.
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') applyFavicon()
  })
}
