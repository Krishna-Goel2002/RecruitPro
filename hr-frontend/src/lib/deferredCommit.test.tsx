import { act, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../design/components'
import { flushDeferredCommits, pendingCommitCount, useDeferredCommit, type DeferredOptions } from './deferredCommit'

let schedule: ((o: DeferredOptions) => number) | null = null
function Harness() {
  schedule = useDeferredCommit()
  return null
}
function mount() {
  return render(<ToastProvider><Harness /></ToastProvider>)
}

beforeEach(() => { vi.useFakeTimers() })
afterEach(async () => {
  await act(async () => { await flushDeferredCommits() })
  vi.useRealTimers()
  schedule = null
})

describe('deferred commit (5 s Undo window)', () => {
  it('sends after the window and not before', async () => {
    mount()
    const commit = vi.fn().mockResolvedValue(undefined)
    act(() => { schedule!({ title: 'Shortlisting Candidate #A-1', commit, revert: vi.fn() }) })
    await act(async () => { vi.advanceTimersByTime(4900) })
    expect(commit).not.toHaveBeenCalled()
    await act(async () => { vi.advanceTimersByTime(200) })
    expect(commit).toHaveBeenCalledTimes(1)
    expect(pendingCommitCount()).toBe(0)
  })

  it('Undo cancels without any request and reverts the optimistic change', async () => {
    mount()
    const commit = vi.fn().mockResolvedValue(undefined)
    const revert = vi.fn()
    act(() => { schedule!({ title: 'Shortlisting Candidate #A-2', commit, revert }) })
    act(() => { screen.getByRole('button', { name: 'Undo' }).click() })
    await act(async () => { vi.advanceTimersByTime(10_000) })
    expect(commit).not.toHaveBeenCalled()
    expect(revert).toHaveBeenCalledTimes(1)
    expect(pendingCommitCount()).toBe(0)
  })

  it('still sends a confirmed decision after the screen that scheduled it is gone', async () => {
    const view = mount()
    const commit = vi.fn().mockResolvedValue(undefined)
    act(() => { schedule!({ title: 'Marking Candidate #A-3 as not progressed', commit, revert: vi.fn() }) })
    view.unmount()
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(commit).toHaveBeenCalledTimes(1)
  })

  it('reverts when the request fails', async () => {
    mount()
    const revert = vi.fn()
    const onError = vi.fn()
    act(() => { schedule!({ title: 'x', commit: () => Promise.reject(new Error('409')), revert, onError }) })
    await act(async () => { vi.advanceTimersByTime(5000) })
    expect(revert).toHaveBeenCalledTimes(1)
    expect(onError).toHaveBeenCalled()
  })

  it('asks before the tab closes while something is pending, even after unmount', async () => {
    const view = mount()
    act(() => { schedule!({ title: 'x', commit: vi.fn().mockResolvedValue(undefined), revert: vi.fn() }) })
    view.unmount()
    const event = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(event)
    expect(event.defaultPrevented).toBe(true)
    await act(async () => { vi.advanceTimersByTime(5000) })
    const after = new Event('beforeunload', { cancelable: true })
    window.dispatchEvent(after)
    expect(after.defaultPrevented).toBe(false)
  })

  it('flushDeferredCommits sends everything now (used before sign-out)', async () => {
    mount()
    const a = vi.fn().mockResolvedValue(undefined)
    const b = vi.fn().mockResolvedValue(undefined)
    act(() => {
      schedule!({ title: 'a', commit: a, revert: vi.fn() })
      schedule!({ title: 'b', commit: b, revert: vi.fn() })
    })
    await act(async () => { await flushDeferredCommits() })
    expect(a).toHaveBeenCalledTimes(1)
    expect(b).toHaveBeenCalledTimes(1)
    await act(async () => { vi.advanceTimersByTime(10_000) })
    expect(a).toHaveBeenCalledTimes(1)
  })
})
