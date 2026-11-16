import { useCallback, useEffect, useRef, useState } from 'react'
import { readStored, writeStored } from './storage'

function persist(key: string, value: string): void {
  writeStored(key, value.trim() ? value : null)
}

/**
 * A text draft autosaved to localStorage under `key` (debounced), restored on mount. `savedAt` drives a quiet
 * "Draft saved" indicator; `clear()` removes it after a successful submit. Drafts are a per-browser convenience
 * (FLOWS-CANDIDATE §14.7): nothing is sent to the server until the candidate submits.
 *
 * Text typed less than `delayMs` before the key changes (the apply dialog closes) or the page unmounts is written
 * at once instead of being dropped with the pending timer.
 */
export function useDraft(key: string | null, delayMs = 800) {
  const [value, setValue] = useState<string>(() => (key ? readStored(key) ?? '' : ''))
  const [savedAt, setSavedAt] = useState<Date | null>(null)
  const [restored, setRestored] = useState(() => (key ? (readStored(key) ?? '').length > 0 : false))
  const [loadedKey, setLoadedKey] = useState(key)
  if (key !== loadedKey) {
    // A different draft (or the same one re-opened): load it before effects run, so nothing is overwritten.
    const stored = key ? readStored(key) ?? '' : ''
    setLoadedKey(key)
    setValue(stored)
    setRestored(stored.length > 0)
    setSavedAt(null)
  }
  const timer = useRef<number>()
  const keyRef = useRef(key)
  keyRef.current = key
  /** The last committed value and whether it still waits for the debounced write (updated in effects only). */
  const latest = useRef(value)
  const pending = useRef(false)

  useEffect(() => {
    if (!key) return
    latest.current = value
    pending.current = true
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => {
      pending.current = false
      persist(key, value)
      if (value.trim()) setSavedAt(new Date())
    }, delayMs)
    return () => window.clearTimeout(timer.current)
  }, [key, value, delayMs])

  // Flush on key change and unmount (this cleanup runs for the old key, before the new key's effects).
  useEffect(() => {
    if (!key) return
    return () => {
      if (!pending.current) return
      pending.current = false
      persist(key, latest.current)
    }
  }, [key])

  const clear = useCallback(() => {
    window.clearTimeout(timer.current)
    pending.current = false
    if (keyRef.current) writeStored(keyRef.current, null)
    setValue('')
    setSavedAt(null)
  }, [])

  return { value, setValue, savedAt, restored, clear }
}
