import { useCallback, useEffect, useRef, useState } from 'react'
import { readStored, writeStored } from './storage'

/**
 * A text draft autosaved to localStorage under `key` (debounced), restored on mount. `savedAt` drives a quiet
 * "Draft saved" indicator; `clear()` removes it after a successful submit. Drafts are a per-browser convenience
 * (FLOWS-CANDIDATE §14.7): nothing is sent to the server until the candidate submits.
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

  useEffect(() => {
    if (!key) return
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(() => {
      writeStored(key, value.trim() ? value : null)
      if (value.trim()) setSavedAt(new Date())
    }, delayMs)
    return () => window.clearTimeout(timer.current)
  }, [key, value, delayMs])

  const clear = useCallback(() => {
    window.clearTimeout(timer.current)
    if (keyRef.current) writeStored(keyRef.current, null)
    setValue('')
    setSavedAt(null)
  }, [])

  return { value, setValue, savedAt, restored, clear }
}
