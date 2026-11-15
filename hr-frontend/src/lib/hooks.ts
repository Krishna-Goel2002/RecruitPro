import { useCallback, useEffect, useRef, useState } from 'react'
import brand from '../design/brand/active'

/** Sets the document title for the current screen. */
export function usePageTitle(title: string) {
  useEffect(() => {
    document.title = title ? `${title} · ${brand.name}` : `${brand.name} ${brand.portalLabel.staff}`.trim()
  }, [title])
}

export function useDebouncedValue<T>(value: T, delayMs = 300): T {
  const [debounced, setDebounced] = useState(value)
  useEffect(() => {
    const timer = window.setTimeout(() => setDebounced(value), delayMs)
    return () => window.clearTimeout(timer)
  }, [value, delayMs])
  return debounced
}

/** Polling stops this long after the first pending response (L8). */
export const POLL_MAX_MS = 120_000

/** Backoff: 2 s at first, growing with elapsed time, capped at 15 s. */
export function pollDelay(startedAt: number | null): number | false {
  if (startedAt == null) return false
  const elapsed = Date.now() - startedAt
  if (elapsed >= POLL_MAX_MS) return false
  // Rounded to whole seconds so re-renders within the same second do not restart React Query's interval timer.
  return Math.round(Math.min(2000 + elapsed / 8, 15_000) / 1000) * 1000
}

/**
 * Poll only while something is pending (e.g. an applicant with matchStatus PENDING), with backoff, and stop after
 * about two minutes. `resetKey` restarts the window (e.g. another job or page); `restart()` after a user action
 * such as re-run matching. Wire it to React Query with usePollRef (the query is declared before its data is known).
 */
export function usePendingPoll(pending: boolean, resetKey: string) {
  const [startedAt, setStartedAt] = useState<number | null>(null)
  const [timedOut, setTimedOut] = useState(false)
  const keyRef = useRef(resetKey)
  const activeStart = useRef<number | null>(null)

  useEffect(() => {
    if (keyRef.current === resetKey) return
    keyRef.current = resetKey
    setStartedAt(null)
    setTimedOut(false)
  }, [resetKey])

  useEffect(() => {
    if (!pending) {
      setStartedAt(null)
      setTimedOut(false)
      return
    }
    setStartedAt(current => current ?? Date.now())
  }, [pending, resetKey])

  useEffect(() => {
    if (startedAt == null || timedOut) return
    const remaining = POLL_MAX_MS - (Date.now() - startedAt)
    if (remaining <= 0) {
      setTimedOut(true)
      return
    }
    const timer = window.setTimeout(() => setTimedOut(true), remaining)
    return () => window.clearTimeout(timer)
  }, [startedAt, timedOut])

  activeStart.current = pending && !timedOut ? startedAt : null
  const pendingRef = useRef(pending)
  pendingRef.current = pending

  const refetchInterval = useCallback(() => pollDelay(activeStart.current), [])
  const restart = useCallback(() => {
    setTimedOut(false)
    setStartedAt(pendingRef.current ? Date.now() : null)
  }, [])

  return { refetchInterval, polling: activeStart.current != null, timedOut: pending && timedOut, restart }
}

/**
 * Lets a query use a polling interval that is computed from its own data:
 *   const poll = usePollRef()
 *   const query = useQuery({ ..., refetchInterval: poll.interval })
 *   const pendingPoll = usePendingPoll(isPending(query.data), key)
 *   poll.bind(pendingPoll.refetchInterval)
 * React Query re-evaluates `interval` after every fetch and on every render.
 */
export function usePollRef() {
  const ref = useRef<() => number | false>(() => false)
  const interval = useCallback(() => ref.current(), [])
  const bind = useCallback((fn: () => number | false) => { ref.current = fn }, [])
  return { interval, bind }
}
