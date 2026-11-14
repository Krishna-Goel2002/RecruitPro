import { useEffect, useId, useRef, type ReactNode, type RefObject } from 'react'

interface DialogProps {
  open: boolean
  title: string
  onClose: () => void
  children: ReactNode
  description?: ReactNode
  /** Element focused when the dialog opens; defaults to the first focusable control. */
  initialFocusRef?: RefObject<HTMLElement>
  /** Prevent closing (Escape, backdrop, close button) while work is in progress. */
  busy?: boolean
  size?: 'small' | 'medium' | 'large'
}

const FOCUSABLE = 'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

/**
 * Modal dialog built on the native <dialog> element: `showModal()` makes the rest of the page inert and keeps
 * focus inside. Focus moves into the dialog on open and returns to the element that opened it on close.
 */
export function Dialog({ open, title, onClose, children, description, initialFocusRef, busy = false, size = 'medium' }: DialogProps) {
  const dialogRef = useRef<HTMLDialogElement>(null)
  const returnFocusRef = useRef<HTMLElement | null>(null)
  const onCloseRef = useRef(onClose)
  onCloseRef.current = onClose
  const busyRef = useRef(busy)
  busyRef.current = busy
  const titleId = useId()
  const descriptionId = useId()

  useEffect(() => {
    const dialog = dialogRef.current
    if (!dialog || !open) return
    returnFocusRef.current = document.activeElement instanceof HTMLElement ? document.activeElement : null
    if (!dialog.open) {
      if (typeof dialog.showModal === 'function') dialog.showModal()
      else dialog.setAttribute('open', '')
    }
    const target = initialFocusRef?.current ?? dialog.querySelector<HTMLElement>(`.dialog-body ${FOCUSABLE}`) ?? dialog.querySelector<HTMLElement>(FOCUSABLE)
    target?.focus()

    const handleCancel = (event: Event) => {
      event.preventDefault()
      if (!busyRef.current) onCloseRef.current()
    }
    dialog.addEventListener('cancel', handleCancel)
    return () => {
      dialog.removeEventListener('cancel', handleCancel)
      if (dialog.open) dialog.close()
      const returnTo = returnFocusRef.current
      if (returnTo && document.contains(returnTo)) returnTo.focus()
    }
    // initialFocusRef is a ref object; reading .current when the dialog opens is enough.
  }, [open, initialFocusRef])

  if (!open) return null

  return (
    <dialog
      ref={dialogRef}
      className={`dialog dialog-${size}`}
      aria-labelledby={titleId}
      aria-describedby={description ? descriptionId : undefined}
      onClick={event => {
        // A click on the backdrop targets the <dialog> element itself.
        if (event.target === event.currentTarget && !busy) onClose()
      }}
    >
      <div className="dialog-panel">
        <header className="dialog-header">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="modal-close" aria-label="Close dialog" onClick={onClose} disabled={busy}>
            <span aria-hidden="true">×</span>
          </button>
        </header>
        {description && (
          <div id={descriptionId} className="dialog-description">
            {description}
          </div>
        )}
        <div className="dialog-body">{children}</div>
      </div>
    </dialog>
  )
}

interface ConfirmDialogProps {
  open: boolean
  title: string
  children: ReactNode
  confirmLabel: string
  onConfirm: () => void
  onCancel: () => void
  busy?: boolean
  tone?: 'danger' | 'primary'
  error?: ReactNode
}

/** Yes/no confirmation. Focus starts on Cancel so a stray Enter never confirms a destructive action. */
export function ConfirmDialog({ open, title, children, confirmLabel, onConfirm, onCancel, busy = false, tone = 'danger', error }: ConfirmDialogProps) {
  const cancelRef = useRef<HTMLButtonElement>(null)
  return (
    <Dialog open={open} title={title} onClose={onCancel} busy={busy} initialFocusRef={cancelRef} size="small">
      <div className="confirm-text">{children}</div>
      {error}
      <div className="dialog-actions">
        <button ref={cancelRef} type="button" className="quiet-button" onClick={onCancel} disabled={busy}>
          Cancel
        </button>
        <button type="button" className={tone === 'danger' ? 'danger-button solid' : 'primary'} onClick={onConfirm} disabled={busy}>
          {busy ? 'Please wait…' : confirmLabel}
        </button>
      </div>
    </Dialog>
  )
}
