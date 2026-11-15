import { useCallback, useEffect, useId, useLayoutEffect, useRef, type MouseEvent as ReactMouseEvent, type ReactNode, type RefObject } from 'react'
import { X } from '../icons'
import { reduced } from '../motion'
import { cx } from './cx'
import { IconButton } from './IconButton'

/**
 * Shared native-<dialog> behaviour for Dialog and Drawer: showModal() (inert background, focus containment, Escape),
 * an exit animation before close(), focus returned to the element that opened it, and `busy` blocking dismissal.
 */
export function useModal(ref: RefObject<HTMLDialogElement>, open: boolean, onClose: () => void, busy: boolean, exitMs: number) {
  const returnTo = useRef<HTMLElement | null>(null)
  const closing = useRef<number>()

  useLayoutEffect(() => {
    const d = ref.current
    if (!d) return
    if (open && !d.open) {
      window.clearTimeout(closing.current)
      delete d.dataset.closing
      returnTo.current = document.activeElement as HTMLElement | null
      d.showModal()
      // Initial focus: an explicit [autofocus]; in a Dialog the first field or button of the content; else the close button.
      if (!d.querySelector('[autofocus]')) {
        const first = d.querySelector<HTMLElement>(
          '.ui-dialog-body :is(input, select, textarea, button, a[href]):not([disabled]), .ui-dialog-footer :is(button, a[href]):not([disabled])',
        )
        first?.focus()
      }
    } else if (!open && d.open) {
      const finish = () => {
        delete d.dataset.closing
        d.close()
        const target = returnTo.current
        if (target && target.isConnected) target.focus({ preventScroll: true })
      }
      if (reduced()) finish()
      else {
        d.dataset.closing = ''
        closing.current = window.setTimeout(finish, exitMs)
      }
    }
  }, [open, ref, exitMs])

  useEffect(() => () => window.clearTimeout(closing.current), [])

  const requestClose = useCallback(() => {
    if (!busy) onClose()
  }, [busy, onClose])

  useEffect(() => {
    const d = ref.current
    if (!d) return
    const onCancel = (e: Event) => {
      e.preventDefault()
      requestClose()
    }
    d.addEventListener('cancel', onCancel)
    return () => d.removeEventListener('cancel', onCancel)
  }, [ref, requestClose])

  /** Light dismiss: a press on the backdrop (the dialog element itself, outside the panel). */
  const onBackdrop = useCallback(
    (e: ReactMouseEvent<HTMLDialogElement>) => {
      if (e.target === e.currentTarget) requestClose()
    },
    [requestClose],
  )
  return { requestClose, onBackdrop }
}

export interface DialogProps {
  open: boolean
  onClose: () => void
  title: ReactNode
  description?: ReactNode
  /** 400 (confirm) · 560 (form, default) · 720 (rich) */
  size?: 'sm' | 'md' | 'lg'
  /** Right-aligned buttons, Cancel first. */
  footer?: ReactNode
  /** Blocks Escape, backdrop and the close button (e.g. while saving). */
  busy?: boolean
  /** Close when the backdrop is pressed. Default true (off automatically while busy). */
  dismissible?: boolean
  /** Destructive confirmation: role="alertdialog". Put Cancel first and give it autoFocus. */
  alert?: boolean
  /** Overline above the title ("Step 2 of 3"). */
  overline?: ReactNode
  children?: ReactNode
  className?: string
}

/** Dialog (§9.15, M8) on native <dialog>: header (h2 styled h3 + close), body padding 20, footer right-aligned. */
export function Dialog({ open, onClose, title, description, size = 'md', footer, busy = false, dismissible = true, alert, overline, children, className }: DialogProps) {
  const ref = useRef<HTMLDialogElement>(null)
  const id = useId()
  const { requestClose, onBackdrop } = useModal(ref, open, onClose, busy, 140)
  return (
    <dialog
      ref={ref}
      className={cx('ui-dialog', className)}
      data-size={size}
      role={alert ? 'alertdialog' : undefined}
      aria-labelledby={`${id}-t`}
      aria-describedby={description ? `${id}-d` : undefined}
      aria-busy={busy || undefined}
      onClick={dismissible ? onBackdrop : undefined}
    >
      <div className="ui-dialog-panel">
        <header className="ui-dialog-header">
          <div className="ui-dialog-titles">
            {overline && <p className="t-overline">{overline}</p>}
            <h2 id={`${id}-t`} className="t-h3">
              {title}
            </h2>
            {description && (
              <p id={`${id}-d`} className="ui-dialog-desc">
                {description}
              </p>
            )}
          </div>
          <IconButton label="Close" icon={X} size="sm" onClick={requestClose} disabled={busy} noTooltip />
        </header>
        {children != null && <div className="ui-dialog-body">{children}</div>}
        {footer && <footer className="ui-dialog-footer">{footer}</footer>}
      </div>
    </dialog>
  )
}
