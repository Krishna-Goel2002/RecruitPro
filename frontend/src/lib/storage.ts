/**
 * Per-viewer conveniences in Web Storage (drafts, dismissed hints). Every access is wrapped: private windows and
 * blocked storage throw, and the app must work without it.
 */
type Area = 'local' | 'session'

function area(which: Area): Storage | null {
  try {
    return which === 'local' ? window.localStorage : window.sessionStorage
  } catch {
    return null
  }
}

export function readStored(key: string, which: Area = 'local'): string | null {
  try {
    return area(which)?.getItem(key) ?? null
  } catch {
    return null
  }
}

export function writeStored(key: string, value: string | null, which: Area = 'local'): void {
  try {
    const store = area(which)
    if (!store) return
    if (value === null) store.removeItem(key)
    else store.setItem(key, value)
  } catch {
    /* quota or blocked storage: the convenience is simply lost */
  }
}

/** Per-browser flag for the Overview checklist step "Review what we read" (FLOWS-CANDIDATE §14.3). */
export const RESUME_REVIEWED_KEY = 'resume-analysis-reviewed'
