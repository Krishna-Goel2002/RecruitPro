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

/**
 * A time-limited polling window for React Query's `refetchInterval`, shared by the resume analysis and the
 * verification runs. `nextInterval(isActive)` returns the next delay (with optional backoff) or `false`;
 * `stalled` turns true once the limit has passed while still active; `restart()` opens a new window.
 */
export function usePollingWindow({ active, intervalMs, backoff = 1, maxIntervalMs = intervalMs, limitMs = POLL_LIMIT_MS }: PollingWindowOptions) {
  const [since, setSince] = useState(() => Date.now())
  const [stalled, setStalled] = useState(false)
  const polls = useRef(0)

  useEffect(() => {
    if (!active) {
      polls.current = 0
      return
    }
    if (stalled) return
    const remaining = limitMs - (Date.now() - since)
    const timer = window.setTimeout(() => setStalled(true), Math.max(0, remaining))
    return () => window.clearTimeout(timer)
  }, [active, stalled, since, limitMs])

  const nextInterval = useCallback(
    (isActive: boolean): number | false => {
      if (stalled || !isActive) return false
      const delay = Math.min(maxIntervalMs, Math.round(intervalMs * backoff ** polls.current))
      polls.current += 1
      return delay
    },
    [stalled, intervalMs, backoff, maxIntervalMs],
  )

  const restart = useCallback(() => {
    polls.current = 0
    setStalled(false)
    setSince(Date.now())
  }, [])

  return { nextInterval, stalled: active && stalled, restart }
}
