/**
 * Per-viewer "since your last visit" markers for jobs (API gap: no server-side lastViewedAt). Stored in this
 * browser only; every access is wrapped because storage can be blocked. Two values per job: the application count
 * and the time when the viewer last opened the job's applicants.
 */
type Seen = { count: number; at: number }
const KEY = 'hr-jobs-seen'

function readAll(): Record<string, Seen> {
  try {
    const raw = window.localStorage.getItem(KEY)
    const parsed = raw ? (JSON.parse(raw) as Record<string, Seen>) : {}
    return parsed && typeof parsed === 'object' ? parsed : {}
  } catch {
    return {}
  }
}

export function lastSeen(jobId: number): Seen | null {
  return readAll()[String(jobId)] ?? null
}

/** Applications that arrived since the viewer last opened this job's applicants (null before the first visit). */
export function newSince(jobId: number, count: number | undefined): number | null {
  const seen = lastSeen(jobId)
  if (!seen || count == null) return null
  return Math.max(0, count - seen.count)
}

export function markSeen(jobId: number, count: number | undefined) {
  if (count == null) return
  try {
    const all = readAll()
    all[String(jobId)] = { count, at: Date.now() }
    // Keep the store small: the 200 most recently seen jobs.
    const trimmed = Object.fromEntries(Object.entries(all).sort((a, b) => b[1].at - a[1].at).slice(0, 200))
    window.localStorage.setItem(KEY, JSON.stringify(trimmed))
  } catch {
    /* storage unavailable: no markers */
  }
}

/** Per-viewer boolean flags (dismissed hints, hidden checklist). */
export function readFlag(key: string): boolean {
  try {
    return window.localStorage.getItem(key) === '1'
  } catch {
    return false
  }
}

export function writeFlag(key: string, value: boolean) {
  try {
    if (value) window.localStorage.setItem(key, '1')
    else window.localStorage.removeItem(key)
  } catch {
    /* ignore */
  }
}
