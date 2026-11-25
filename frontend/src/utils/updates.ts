// Checks GitHub for a newer Synchaven release. Runs in the browser only; the result is cached in
// localStorage so the unauthenticated GitHub API (60 requests/hour per IP) is not hit on every visit.

const REPO = 'daved09/Synchaven'
const CACHE_KEY = 'synchaven.latestRelease'
const CACHE_TTL_MS = 6 * 60 * 60 * 1000

export interface ReleaseInfo {
  /** Version without a leading "v", e.g. "1.2.0" */
  version: string
  url: string
}

interface CacheEntry {
  checkedAt: number
  release: ReleaseInfo
}

function parse(version: string): number[] {
  return version.trim().replace(/^v/i, '').split(/[.+-]/).slice(0, 3).map((p) => parseInt(p, 10) || 0)
}

/** > 0 if a is newer than b, < 0 if older, 0 if equal (major.minor.patch) */
export function compareVersions(a: string, b: string): number {
  const pa = parse(a)
  const pb = parse(b)
  for (let i = 0; i < 3; i++) {
    const d = (pa[i] ?? 0) - (pb[i] ?? 0)
    if (d) return d
  }
  return 0
}

/** Latest published (non-draft, non-prerelease) release on GitHub; `null` if it cannot be fetched */
export async function fetchLatestRelease(force = false): Promise<ReleaseInfo | null> {
  if (!force) {
    try {
      const cached = JSON.parse(localStorage.getItem(CACHE_KEY) ?? 'null') as CacheEntry | null
      if (cached && Date.now() - cached.checkedAt < CACHE_TTL_MS) return cached.release
    } catch {
      // ignore broken cache entries
    }
  }
  try {
    const res = await fetch(`https://api.github.com/repos/${REPO}/releases/latest`, {
      headers: { Accept: 'application/vnd.github+json' },
    })
    if (!res.ok) return null
    const json = (await res.json()) as { tag_name?: string; html_url?: string }
    if (!json.tag_name) return null
    const release: ReleaseInfo = {
      version: json.tag_name.replace(/^v/i, ''),
      url: json.html_url ?? `https://github.com/${REPO}/releases/latest`,
    }
    localStorage.setItem(CACHE_KEY, JSON.stringify({ checkedAt: Date.now(), release } satisfies CacheEntry))
    return release
  } catch {
    // offline, blocked or rate limited – the check is optional
    return null
  }
}

/** The newer release if one exists, otherwise `null` */
export async function checkForUpdate(currentVersion: string, force = false): Promise<ReleaseInfo | null> {
  const latest = await fetchLatestRelease(force)
  return latest && compareVersions(latest.version, currentVersion) > 0 ? latest : null
}
