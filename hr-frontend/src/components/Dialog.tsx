import { FormEvent, KeyboardEvent as ReactKeyboardEvent, ReactNode, RefObject, useEffect, useId, useRef, useState } from 'react'
import { createPortal } from 'react-dom'
import { FormError } from './States'
import { CloseIcon } from './Icons'

const FOCUSABLE = 'a[href], button:not([disabled]), input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])'

let openDialogs = 0

/**
 * Modal dialog: focus moves inside on open, Tab is trapped, Escape closes (unless busy), the page behind is inert,
 * and focus returns to the element that opened it.
 */
export function Dialog({ open, title, description, onClose, children, footer, wide = false, busy = false, initialFocusRef }: {
  open: boolean
  title: string
  description?: ReactNode
  onClose: () => void
  children?: ReactNode
  footer?: ReactNode
  wide?: boolean
  busy?: boolean
  initialFocusRef?: RefObject<HTMLElement>
}) {
  const titleId = useId()
  const descriptionId = useId()
  const panelRef = useRef<HTMLDivElement>(null)
  const closeRef = useRef(onClose)
  closeRef.current = onClose
  const busyRef = useRef(busy)
  busyRef.current = busy

  useEffect(() => {
    if (!open) return
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const root = document.getElementById('root')
    openDialogs += 1
    root?.setAttribute('inert', '')
    root?.setAttribute('aria-hidden', 'true')

    const panel = panelRef.current
    const target = initialFocusRef?.current
      ?? panel?.querySelector<HTMLElement>('[data-autofocus]')
      ?? panel?.querySelector<HTMLElement>('.dialog-body ' + FOCUSABLE.split(', ').join(', .dialog-body '))
      ?? panel
    window.setTimeout(() => target?.focus(), 0)

    return () => {
      openDialogs -= 1
      if (openDialogs <= 0) {
        openDialogs = 0
        root?.removeAttribute('inert')
        root?.removeAttribute('aria-hidden')
      }
      if (previous && document.contains(previous)) window.setTimeout(() => previous.focus(), 0)
    }
    // initialFocusRef is read once on open
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open])

  if (!open) return null

  function onKeyDown(event: ReactKeyboardEvent<HTMLDivElement>) {
    if (event.key === 'Escape') {
      event.stopPropagation()
      if (!busyRef.current) closeRef.current()
      return
    }
    if (event.key !== 'Tab' || !panelRef.current) return
    const items = Array.from(panelRef.current.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(item => item.offsetParent !== null || item === document.activeElement)
    if (items.length === 0) {
      event.preventDefault()
      return
    }
    const first = items[0]
    const last = items[items.length - 1]
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault()
      last.focus()
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault()
      first.focus()
    }
  }

  return createPortal(
    <div className="dialog-backdrop" onMouseDown={event => { if (event.target === event.currentTarget && !busy) onClose() }}>
      <div
        ref={panelRef}
        className={`dialog-panel${wide ? ' wide' : ''}`}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={description ? descriptionId : undefined}
        tabIndex={-1}
        onKeyDown={onKeyDown}
      >
        <div className="dialog-head">
          <h2 id={titleId}>{title}</h2>
          <button type="button" className="icon-button" onClick={onClose} disabled={busy} aria-label="Close dialog"><CloseIcon /></button>
        </div>
        {description && <div className="dialog-description" id={descriptionId}>{description}</div>}
        {children && <div className="dialog-body">{children}</div>}
        {footer && <div className="dialog-footer">{footer}</div>}
      </div>
    </div>,
    document.body,
  )
}

/** Yes/no confirmation. */
export function ConfirmDialog({ open, title, message, confirmLabel, danger = false, busy = false, error, onConfirm, onCancel }: {
  open: boolean
  title: string
  message: ReactNode
  confirmLabel: string
  danger?: boolean
  busy?: boolean
  error?: unknown
  onConfirm: () => void
  onCancel: () => void
}) {
  const confirmRef = useRef<HTMLButtonElement>(null)
  const cancelRef = useRef<HTMLButtonElement>(null)
  return (
    <Dialog
      open={open}
      title={title}
      description={message}
      onClose={onCancel}
      busy={busy}
      // Destructive confirmations start on Cancel so Enter does not destroy anything by accident.
      initialFocusRef={danger ? cancelRef : confirmRef}
      footer={<>
        <FormError error={error} />
        <div className="dialog-actions">
          <button ref={cancelRef} type="button" className="outline-button" onClick={onCancel} disabled={busy}>Cancel</button>
          <button ref={confirmRef} type="button" className={danger ? 'danger-button' : 'primary-button workspace-primary'} onClick={onConfirm} disabled={busy}>
            {busy ? 'Working…' : confirmLabel}
          </button>
        </div>
      </>}
    />
  )
}

/** Asks for a comment, note or reason before an action; `required` blocks empty submissions. */
export function TextPromptDialog({ open, title, message, label, placeholder, required = false, maxLength = 2000, confirmLabel, danger = false, busy = false, error, initialValue = '', onSubmit, onCancel, children }: {
  open: boolean
  title: string
  message?: ReactNode
  label: string
  placeholder?: string
  required?: boolean
  maxLength?: number
  confirmLabel: string
  danger?: boolean
  busy?: boolean
  error?: unknown
  initialValue?: string
  onSubmit: (text: string) => void
  onCancel: () => void
  children?: ReactNode
}) {
  const [text, setText] = useState(initialValue)
  const [touched, setTouched] = useState(false)
  const fieldId = useId()
  const textRef = useRef<HTMLTextAreaElement>(null)

  useEffect(() => {
    if (open) {
      setText(initialValue)
      setTouched(false)
    }
  }, [open, initialValue])

  const missing = required && !text.trim()

  function submit(event: FormEvent) {
    event.preventDefault()
    setTouched(true)
    if (missing) {
      textRef.current?.focus()
      return
    }
    onSubmit(text.trim())
  }

  return (
    <Dialog open={open} title={title} description={message} onClose={onCancel} busy={busy} initialFocusRef={textRef}>
      <form className="dialog-form" onSubmit={submit} noValidate>
        {children}
        <div className="field">
          <label htmlFor={fieldId}>{label} {required ? <span className="required-mark">*</span> : <span className="optional-mark">(optional)</span>}</label>
          <textarea
            ref={textRef}
            id={fieldId}
            rows={4}
            maxLength={maxLength}
            value={text}
            placeholder={placeholder}
            onChange={event => setText(event.target.value)}
            aria-invalid={touched && missing}
            aria-describedby={touched && missing ? `${fieldId}-error` : undefined}
            required={required}
          />
          {touched && missing && <small className="field-error" id={`${fieldId}-error`}>This field is required.</small>}
        </div>
        <FormError error={error} />
        <div className="dialog-actions">
          <button type="button" className="outline-button" onClick={onCancel} disabled={busy}>Cancel</button>
          <button type="submit" className={danger ? 'danger-button' : 'primary-button workspace-primary'} disabled={busy}>
            {busy ? 'Saving…' : confirmLabel}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
