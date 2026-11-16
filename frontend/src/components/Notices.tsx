import { useCallback } from 'react'
import { useToast, type ToastOptions } from '../design/components'

export type NoticeKind = 'success' | 'info' | 'error'

const TONE: Record<NoticeKind, NonNullable<ToastOptions['tone']>> = { success: 'success', info: 'info', error: 'danger' }

/**
 * Page notices, now delivered as design-system toasts (§9.17). The old `notify(kind, text)` signature is kept so
 * call sites stay short; pass `extra` for a body line or one action (e.g. Undo, Review).
 * Toasts live outside #root, so they survive navigation and are announced even while a dialog makes the page inert.
 */
export function useNotify() {
  const { toast } = useToast()
  return useCallback(
    (kind: NoticeKind, text: string, extra?: Pick<ToastOptions, 'body' | 'action' | 'duration'>) =>
      toast({ tone: TONE[kind], title: text, ...extra }),
    [toast],
  )
}
