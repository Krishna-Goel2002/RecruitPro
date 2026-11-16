import { useCallback } from 'react'
import { useToast } from '../design/components'

/**
 * "Send in 5 s, Undo" for actions that leave the building (decisions notify candidates and are audited). The UI
 * changes optimistically at once; the request is sent only when the window passes, so Undo needs no server call and
 * leaves no audit noise.
 *
 * Pending commits live at module level on purpose: navigating to another screen inside the app does not cancel a
 * decision the user confirmed (it is still sent when its window passes). Closing or reloading the tab while one is
 * pending makes the browser ask first (the listener is registered while anything is pending, independent of which
 * component scheduled it). Signing out sends pending commits first (`flushDeferredCommits`).
 */
type Pending = { timer: number; run: () => Promise<void> }
const pending = new Map<number, Pending>()
let seq = 0
let listening = false

function onBeforeUnload(event: BeforeUnloadEvent) {
  if (pending.size === 0) return
  event.preventDefault()
  event.returnValue = ''
}

function syncUnloadGuard() {
  if (typeof window === 'undefined') return
  if (pending.size > 0 && !listening) {
    window.addEventListener('beforeunload', onBeforeUnload)
    listening = true
  } else if (pending.size === 0 && listening) {
    window.removeEventListener('beforeunload', onBeforeUnload)
    listening = false
  }
}

/** Number of commits still inside their Undo window (tests, sign-out). */
export function pendingCommitCount(): number {
  return pending.size
}

export interface DeferredOptions {
  /** Toast title, e.g. "Shortlisting Candidate #A-3014". */
  title: string
  body?: string
  /** ms before the request is sent. Default 5000. */
  delay?: number
  commit: () => Promise<unknown>
  /** Revert the optimistic change (Undo pressed, or the request failed). */
  revert: () => void
  onCommitted?: () => void
  onError?: (cause: unknown) => void
}

export function useDeferredCommit() {
  const { toast, dismiss } = useToast()

  return useCallback(
    (options: DeferredOptions) => {
      const id = ++seq
      const delay = options.delay ?? 5000
      let toastId = 0
      const run = async () => {
        if (!pending.delete(id)) return
        syncUnloadGuard()
        dismiss(toastId)
        try {
          await options.commit()
          options.onCommitted?.()
        } catch (cause) {
          options.revert()
          options.onError?.(cause)
        }
      }
      const timer = window.setTimeout(() => { void run() }, delay)
      pending.set(id, { timer, run })
      syncUnloadGuard()
      toastId = toast({
        tone: 'info',
        title: options.title,
        body: options.body ?? `Sending in ${Math.round(delay / 1000)} seconds.`,
        duration: delay,
        action: {
          label: 'Undo',
          onClick: () => {
            const entry = pending.get(id)
            if (!entry) return
            window.clearTimeout(entry.timer)
            pending.delete(id)
            syncUnloadGuard()
            options.revert()
          },
        },
      })
      return id
    },
    [toast, dismiss],
  )
}

/** Sends every pending commit now (e.g. before signing out) and resolves when they have all finished. */
export function flushDeferredCommits(): Promise<void> {
  const entries = [...pending.values()]
  entries.forEach(entry => window.clearTimeout(entry.timer))
  return Promise.all(entries.map(entry => entry.run())).then(() => undefined)
}
