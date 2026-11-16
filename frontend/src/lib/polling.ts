import { useCallback, useEffect, useRef, useState } from 'react'

/** Default polling window: stop after about two minutes; the user can check again manually (audit L8). */
export const POLL_LIMIT_MS = 120_000

export interface PollingWindowOptions {
  /** Whether the polled resource is still in progress. */
  active: boolean
  /** First interval. */
  intervalMs: number
  /** Multiply the interval by this after every poll (1 = fixed interval). */
  backoff?: number
  /** Upper bound for the interval. */
  maxIntervalMs?: number
  /** Stop polling this long after the window started. */
  limitMs?: number
}

/** The delay before poll number `n` (0-based) of a window. Pure, so it can be tested on its own. */
export function pollDelay(n: number, intervalMs: number, backoff = 1, maxIntervalMs = intervalMs): number {
  return Math.min(maxIntervalMs, Math.round(intervalMs * backoff ** Math.max(0, n)))
}

/**
 * A time-limited polling window for React Query's `refetchInterval`, shared by the resume analysis and the
 * verification runs.
 *
 * - `nextInterval(isActive, fetchCount)` returns the next delay or `false`. It is a pure function of the query state:
 *   React Query evaluates `refetchInterval` on every render (`setOptions`), and a callback with side effects there
 *   (the earlier `polls.current += 1`) reset the interval timer and jumped the backoff to its maximum on re-renders.
 *   Pass `query.state.dataUpdateCount + query.state.errorUpdateCount` as `fetchCount`: the delay grows per response.
 * - A new window opens whenever `active` turns from false to true (a new run, a re-check, a retried analysis), so a
 *   second run started minutes after the page opened is not reported as stalled at once.
 * - `stalled` turns true once the limit has passed while still active; `restart()` opens a new window by hand.
 */
export function usePollingWindow({ active, intervalMs, backoff = 1, maxIntervalMs = intervalMs, limitMs = POLL_LIMIT_MS }: PollingWindowOptions) {
  const [since, setSince] = useState(() => Date.now())
  const [stalled, setStalled] = useState(false)
  const [wasActive, setWasActive] = useState(active)
  // Derived-state update during render (React docs, "storing information from previous renders").
  if (active !== wasActive) {
    setWasActive(active)
    if (active) {
      setSince(Date.now())
      setStalled(false)
    }
  }
  /** The fetch count seen first in the current window: polls are counted from there. */
  const base = useRef<{ since: number; count: number } | null>(null)

  useEffect(() => {
    if (!active || stalled) return
    const remaining = limitMs - (Date.now() - since)
    const timer = window.setTimeout(() => setStalled(true), Math.max(0, remaining))
    return () => window.clearTimeout(timer)
  }, [active, stalled, since, limitMs])

  const nextInterval = useCallback(
    (isActive: boolean, fetchCount = 0): number | false => {
      if (stalled || !isActive) return false
      if (!base.current || base.current.since !== since) base.current = { since, count: fetchCount }
      return pollDelay(fetchCount - base.current.count, intervalMs, backoff, maxIntervalMs)
    },
    [stalled, since, intervalMs, backoff, maxIntervalMs],
  )

  const restart = useCallback(() => {
    setStalled(false)
    setSince(Date.now())
  }, [])

  return { nextInterval, stalled: active && stalled, restart }
}
