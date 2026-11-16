import { useCallback, useEffect, useRef, useState } from 'react'

/**
 * A draft kept in this browser only (scorecards, review notes): survives a reload or a lost connection, never leaves
 * the device, and is cleared once the real save succeeds. Every storage access is wrapped: private windows and
 * blocked storage simply mean "no draft".
 */
const PREFIX = 'draft:'

export function readDraft<T>(key: string): { value: T; savedAt: number } | null {
  try {
    const raw = window.localStorage.getItem(PREFIX + key)
    if (!raw) return null
    const parsed = JSON.parse(raw) as { value: T; savedAt: number }
    return parsed && typeof parsed === 'object' && 'value' in parsed ? parsed : null
  } catch {
    return null
  }
}

export function writeDraft<T>(key: string, value: T): number | null {
  try {
    const savedAt = Date.now()
    window.localStorage.setItem(PREFIX + key, JSON.stringify({ value, savedAt }))
    return savedAt
  } catch {
    return null
  }
}

export function clearDraft(key: string) {
  try {
    window.localStorage.removeItem(PREFIX + key)
  } catch {
    /* storage unavailable: nothing to clear */
  }
}

/**
 * Debounced autosave of `value` under `key` while `enabled`. Returns the last save time (null before the first save
 * or when storage is unavailable) and `clear()` for after a successful submit.
 */
export function useDraftAutosave<T>(key: string, value: T, { enabled = true, delayMs = 600 }: { enabled?: boolean; delayMs?: number } = {}) {
  const [savedAt, setSavedAt] = useState<number | null>(() => readDraft(key)?.savedAt ?? null)
  const first = useRef(true)
  const serialised = JSON.stringify(value)
  useEffect(() => {
    if (first.current) {
      first.current = false
      return
    }
    if (!enabled) return
    const timer = window.setTimeout(() => setSavedAt(writeDraft(key, JSON.parse(serialised) as T)), delayMs)
    return () => window.clearTimeout(timer)
  }, [key, serialised, enabled, delayMs])
  const clear = useCallback(() => {
    clearDraft(key)
    setSavedAt(null)
  }, [key])
  return { savedAt, clear }
}
