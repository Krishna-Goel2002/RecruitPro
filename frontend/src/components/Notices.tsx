import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useLocation } from 'react-router-dom'

export type NoticeKind = 'success' | 'info' | 'error'

interface Notice {
  id: number
  kind: NoticeKind
  text: string
  createdAt: number
}

interface NoticeContextValue {
  notify: (kind: NoticeKind, text: string) => void
}

const NoticeContext = createContext<NoticeContextValue | null>(null)

const AUTO_DISMISS_MS = 6000
const MAX_NOTICES = 2
/** Notices raised just before a navigation (e.g. "account created" → /jobs) survive it; older ones do not. */
const SURVIVE_NAVIGATION_MS = 1500

/**
 * Page-level notices. Both live regions are always in the DOM so screen readers announce new content:
 * success/info politely, errors assertively. Errors stay until dismissed.
 */
export function NoticeProvider({ children }: { children: ReactNode }) {
  const [notices, setNotices] = useState<Notice[]>([])
  const nextId = useRef(1)
  const timers = useRef(new Map<number, number>())

  const dismiss = useCallback((id: number) => {
    setNotices(current => current.filter(notice => notice.id !== id))
    const timer = timers.current.get(id)
    if (timer !== undefined) {
      window.clearTimeout(timer)
      timers.current.delete(id)
    }
  }, [])

  const { pathname } = useLocation()
  useEffect(() => {
    const cutoff = Date.now() - SURVIVE_NAVIGATION_MS
    setNotices(current => current.filter(notice => notice.kind === 'error' || notice.createdAt >= cutoff))
  }, [pathname])

  const notify = useCallback(
    (kind: NoticeKind, text: string) => {
      const id = nextId.current++
      setNotices(current =>
        [...current.filter(notice => notice.text !== text), { id, kind, text, createdAt: Date.now() }].slice(-MAX_NOTICES),
      )
      if (kind !== 'error') {
        timers.current.set(
          id,
          window.setTimeout(() => dismiss(id), AUTO_DISMISS_MS),
        )
      }
    },
    [dismiss],
  )

  useEffect(() => {
    const pending = timers.current
    return () => pending.forEach(timer => window.clearTimeout(timer))
  }, [])

  const value = useMemo(() => ({ notify }), [notify])
  const polite = notices.filter(notice => notice.kind !== 'error')
  const assertive = notices.filter(notice => notice.kind === 'error')

  return (
    <NoticeContext.Provider value={value}>
      {children}
      <div className="notice-region">
        <div aria-live="polite" aria-atomic="false" className="notice-stack">
          {polite.map(notice => (
            <NoticeItem key={notice.id} notice={notice} onDismiss={dismiss} />
          ))}
        </div>
        <div aria-live="assertive" aria-atomic="false" className="notice-stack">
          {assertive.map(notice => (
            <NoticeItem key={notice.id} notice={notice} onDismiss={dismiss} />
          ))}
        </div>
      </div>
    </NoticeContext.Provider>
  )
}

function NoticeItem({ notice, onDismiss }: { notice: Notice; onDismiss: (id: number) => void }) {
  return (
    <div className={`notice notice-${notice.kind}`}>
      <span>{notice.text}</span>
      <button type="button" aria-label="Dismiss message" onClick={() => onDismiss(notice.id)}>
        <span aria-hidden="true">×</span>
      </button>
    </div>
  )
}

export function useNotify(): NoticeContextValue['notify'] {
  const context = useContext(NoticeContext)
  if (!context) throw new Error('useNotify must be used inside <NoticeProvider>')
  return context.notify
}
