import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { POLL_LIMIT_MS, pollDelay, usePollingWindow } from './polling'

beforeEach(() => {
  vi.useFakeTimers()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('pollDelay', () => {
  it('grows by the backoff factor and stops at the maximum', () => {
    expect(pollDelay(0, 3000, 1.3, 15_000)).toBe(3000)
    expect(pollDelay(1, 3000, 1.3, 15_000)).toBe(3900)
    expect(pollDelay(2, 3000, 1.3, 15_000)).toBe(5070)
    expect(pollDelay(20, 3000, 1.3, 15_000)).toBe(15_000)
    expect(pollDelay(5, 3000)).toBe(3000)
  })
})

describe('usePollingWindow', () => {
  const options = { intervalMs: 3000, backoff: 1.3, maxIntervalMs: 15_000 }

  it('is pure for one query state: React Query re-evaluates refetchInterval on every render', () => {
    const { result, rerender } = renderHook(({ active }) => usePollingWindow({ active, ...options }), { initialProps: { active: true } })
    // Many renders with the same fetch count must keep the same delay (they used to push the backoff to 15 s).
    for (let i = 0; i < 10; i++) {
      rerender({ active: true })
      expect(result.current.nextInterval(true, 1)).toBe(3000)
    }
    expect(result.current.nextInterval(true, 2)).toBe(3900)
    expect(result.current.nextInterval(true, 3)).toBe(5070)
  })

  it('returns false when the work is not active', () => {
    const { result } = renderHook(() => usePollingWindow({ active: false, ...options }))
    expect(result.current.nextInterval(false, 4)).toBe(false)
    expect(result.current.stalled).toBe(false)
  })

  it('stalls after the limit and stops polling; restart() opens a new window', () => {
    const { result } = renderHook(() => usePollingWindow({ active: true, ...options }))
    act(() => {
      vi.advanceTimersByTime(POLL_LIMIT_MS + 10)
    })
    expect(result.current.stalled).toBe(true)
    expect(result.current.nextInterval(true, 9)).toBe(false)
    act(() => result.current.restart())
    expect(result.current.stalled).toBe(false)
    // Counting restarts from the first fetch seen in the new window.
    expect(result.current.nextInterval(true, 9)).toBe(3000)
    expect(result.current.nextInterval(true, 10)).toBe(3900)
  })

  it('opens a fresh window when work starts again (a re-check minutes after the page opened)', () => {
    const { result, rerender } = renderHook(({ active }) => usePollingWindow({ active, ...options }), { initialProps: { active: false } })
    // The page has been open for 10 minutes with nothing running.
    act(() => {
      vi.advanceTimersByTime(10 * 60_000)
    })
    rerender({ active: true })
    expect(result.current.stalled).toBe(false)
    expect(result.current.nextInterval(true, 30)).toBe(3000)
    act(() => {
      vi.advanceTimersByTime(POLL_LIMIT_MS - 1000)
    })
    expect(result.current.stalled).toBe(false)
    act(() => {
      vi.advanceTimersByTime(2000)
    })
    expect(result.current.stalled).toBe(true)
    // Finished, then a new run: not stalled, counting again from 3 s.
    rerender({ active: false })
    rerender({ active: true })
    expect(result.current.stalled).toBe(false)
    expect(result.current.nextInterval(true, 50)).toBe(3000)
  })
})
