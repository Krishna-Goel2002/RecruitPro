import { useCallback, useEffect } from 'react'
import { useToast } from '../design/components'

/**
 * "Send in 5 s, Undo" for actions that leave the building (decisions notify candidates and are audited). The UI
 * changes optimistically at once; the request is sent only when the window passes, so Undo needs no server call and
 * leaves no audit noise. Pending commits are flushed if the page is closed (the browser asks first).
 */
type Pending = { timer: number; run: () => void }
const pending = new Map<number, Pending>()
let seq = 0

function onBeforeUnload(event: BeforeUnloadEvent) {
  if (pending.size === 0) return
  event.preventDefault()
  event.returnValue = ''
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

  useEffect(() => {
    window.addEventListener('beforeunload', onBeforeUnload)
    return () => window.removeEventListener('beforeunload', onBeforeUnload)
  }, [])

  return useCallback(
    (options: DeferredOptions) => {
      const id = ++seq
      const delay = options.delay ?? 5000
      let toastId = 0
      const run = () => {
        pending.delete(id)
        dismiss(toastId)
        options
          .commit()
          .then(() => options.onCommitted?.())
          .catch(cause => {
            options.revert()
            options.onError?.(cause)
          })
      }
      const timer = window.setTimeout(run, delay)
      pending.set(id, { timer, run })
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
            options.revert()
          },
        },
      })
      return id
    },
    [toast, dismiss],
  )
}

/** Sends every pending commit now (e.g. before a bulk refresh). */
export function flushDeferredCommits() {
  for (const [, entry] of [...pending]) {
    window.clearTimeout(entry.timer)
    entry.run()
  }
}
