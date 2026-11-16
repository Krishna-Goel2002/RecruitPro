import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { CircleAlert, CircleCheck, Icon, Info, TriangleAlert, X, type IconComponent } from '../icons'
import { reduced } from '../motion'

export type ToastTone = 'success' | 'info' | 'warning' | 'danger'

export interface ToastOptions {
  tone?: ToastTone
  title: string
  body?: ReactNode
  /** One action only (e.g. Undo). */
  action?: { label: string; onClick: () => void }
  /** ms; success/info default 6000, warning/danger stay until dismissed. 0 = sticky. */
  duration?: number
}

interface ToastItem extends Required<Pick<ToastOptions, 'tone' | 'title'>> {
  id: number
  body?: ReactNode
  action?: ToastOptions['action']
  duration: number
  leaving?: boolean
}

interface ToastApi {
  toast: (t: ToastOptions) => number
  dismiss: (id: number) => void
}

const ToastContext = createContext<ToastApi | null>(null)

const ICONS: Record<ToastTone, IconComponent> = { success: CircleCheck, info: Info, warning: TriangleAlert, danger: CircleAlert }

/** useToast().toast({ tone: 'success', title: 'Saved' }) */
export function useToast(): ToastApi {
  const ctx = useContext(ToastContext)
  if (!ctx) throw new Error('useToast must be used inside <ToastProvider>')
  return ctx
}

/**
 * Toasts (§9.17, M10): a region portalled outside #root, bottom-right (bottom-centre on phones), at most three
 * visible; a 3 px tone bar, icon, title, optional body and one action. Success/info auto-dismiss after 6 s with a
 * 2 px progress line that pauses on hover/focus; errors stay. Announced through persistent polite/assertive live
 * regions so screen readers always hear them.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [items, setItems] = useState<ToastItem[]>([])
  const [polite, setPolite] = useState('')
  const [assertive, setAssertive] = useState('')
  const seq = useRef(0)

  const remove = useCallback((id: number) => setItems(list => list.filter(t => t.id !== id)), [])

  const dismiss = useCallback(
    (id: number) => {
      if (reduced()) return remove(id)
      setItems(list => list.map(t => (t.id === id ? { ...t, leaving: true } : t)))
      window.setTimeout(() => remove(id), 140)
    },
    [remove],
  )

  const toast = useCallback((t: ToastOptions) => {
    const id = ++seq.current
    const tone = t.tone ?? 'info'
    const duration = t.duration ?? (tone === 'success' || tone === 'info' ? 6000 : 0)
    setItems(list => [...list, { id, tone, title: t.title, body: t.body, action: t.action, duration }].slice(-3))
    const text = `${t.title}${typeof t.body === 'string' ? `. ${t.body}` : ''}`
    if (tone === 'danger' || tone === 'warning') {
      setAssertive('')
      window.setTimeout(() => setAssertive(text), 50)
    } else {
      setPolite('')
      window.setTimeout(() => setPolite(text), 50)
    }
    return id
  }, [])

  const api = useMemo(() => ({ toast, dismiss }), [toast, dismiss])

  return (
    <ToastContext.Provider value={api}>
      {children}
      {createPortal(
        <div className="ui-toast-region">
          <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">
            {polite}
          </div>
          <div className="sr-only" role="alert" aria-live="assertive" aria-atomic="true">
            {assertive}
          </div>
          <ol className="ui-toast-list" aria-label="Notifications">
            {items.map(t => (
              <li key={t.id} className="ui-toast" data-tone={t.tone} data-leaving={t.leaving || undefined}>
                <Icon icon={ICONS[t.tone]} size={16} className="ui-toast-icon" />
                <div className="ui-toast-text">
                  <p className="ui-toast-title">{t.title}</p>
                  {t.body && <div className="ui-toast-body">{t.body}</div>}
                </div>
                {t.action && (
                  <button
                    type="button"
                    className="ui-toast-action"
                    onClick={() => {
                      t.action!.onClick()
                      dismiss(t.id)
                    }}
                  >
                    {t.action.label}
                  </button>
                )}
                <button type="button" className="ui-toast-close" aria-label="Dismiss notification" onClick={() => dismiss(t.id)}>
                  <Icon icon={X} size={14} />
                </button>
                {t.duration > 0 && (
                  <span
                    className="ui-toast-progress"
                    aria-hidden="true"
                    style={{ animationDuration: `${t.duration}ms` }}
                    onAnimationEnd={() => dismiss(t.id)}
                  />
                )}
              </li>
            ))}
          </ol>
        </div>,
        document.body,
      )}
    </ToastContext.Provider>
  )
}
