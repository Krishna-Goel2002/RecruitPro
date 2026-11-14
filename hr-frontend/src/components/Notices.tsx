import { createContext, ReactNode, useCallback, useContext, useMemo, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { CloseIcon } from './Icons'

type NoticeKind = 'success' | 'error' | 'info'
type Notice = { id: number; kind: NoticeKind; message: string }

type NoticeApi = {
  notify: (message: string, kind?: NoticeKind) => void
  success: (message: string) => void
  error: (message: string) => void
}

const NoticeContext = createContext<NoticeApi | null>(null)

/**
 * Announces outcomes through aria-live regions (polite for success/info, assertive for errors). The regions are
 * rendered outside #root so they stay audible while a modal makes the page inert.
 */
export function NoticeProvider({ children }: { children: ReactNode }) {
  const [notices, setNotices] = useState<Notice[]>([])
  const nextId = useRef(1)

  const dismiss = useCallback((id: number) => setNotices(current => current.filter(notice => notice.id !== id)), [])

  const notify = useCallback((message: string, kind: NoticeKind = 'info') => {
    const id = nextId.current++
    setNotices(current => [...current.slice(-3), { id, kind, message }])
    window.setTimeout(() => dismiss(id), kind === 'error' ? 9000 : 6000)
  }, [dismiss])

  const api = useMemo<NoticeApi>(() => ({
    notify,
    success: message => notify(message, 'success'),
    error: message => notify(message, 'error'),
  }), [notify])

  const polite = notices.filter(notice => notice.kind !== 'error')
  const assertive = notices.filter(notice => notice.kind === 'error')

  return (
    <NoticeContext.Provider value={api}>
      {children}
      {createPortal(
        <div className="toast-stack">
          <div role="status" aria-live="polite" aria-atomic="false">
            {polite.map(notice => <Toast key={notice.id} notice={notice} onDismiss={dismiss} />)}
          </div>
          <div role="alert" aria-live="assertive" aria-atomic="false">
            {assertive.map(notice => <Toast key={notice.id} notice={notice} onDismiss={dismiss} />)}
          </div>
        </div>,
        document.body,
      )}
    </NoticeContext.Provider>
  )
}

function Toast({ notice, onDismiss }: { notice: Notice; onDismiss: (id: number) => void }) {
  return (
    <div className={`toast toast-${notice.kind}`}>
      <span>{notice.message}</span>
      <button type="button" className="icon-button" onClick={() => onDismiss(notice.id)} aria-label="Dismiss message"><CloseIcon /></button>
    </div>
  )
}

export function useNotices(): NoticeApi {
  const value = useContext(NoticeContext)
  if (!value) throw new Error('useNotices must be used inside NoticeProvider')
  return value
}
