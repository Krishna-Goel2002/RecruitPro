import { useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react'
import { Button, Dialog, Field, Textarea } from '../design/components'
import { InlineError } from './QueryError'
import './staff-ui.css'

export interface ActionDialogComment {
  label?: string
  required?: boolean
  placeholder?: string
  maxLength?: number
  hint?: ReactNode
  /** Quick reasons: pressing one fills (or appends to) the comment. */
  suggestions?: string[]
  /** Message when a required comment is missing. */
  requiredMessage?: string
}

export interface ActionDialogProps {
  open: boolean
  title: ReactNode
  description?: ReactNode
  /** Extra content between the description and the comment (a "what becomes visible" list, a last check…). */
  children?: ReactNode
  comment?: ActionDialogComment
  confirmLabel: string
  /**
   * Label of the dismiss button (default "Cancel"). Use "Keep …" when the action itself is a cancellation
   * ("Cancel interview"), so the two buttons never read alike.
   */
  cancelLabel?: string
  /** danger: a destructive confirmation (role alertdialog, Cancel focused first, solid danger button). */
  tone?: 'primary' | 'danger'
  busy?: boolean
  error?: unknown
  overline?: ReactNode
  size?: 'sm' | 'md' | 'lg'
  /** Irreversible but not destructive (reveal): role alertdialog and Cancel focused first, with a normal primary button. */
  focusCancel?: boolean
  onCancel: () => void
  onConfirm: (comment: string) => void
}

/**
 * One confirmation pattern for every Part B action (decisions, reveal, cancel, revoke, role change): a native-dialog
 * Dialog, an optional comment with quick reasons, the server error inline, Cancel first. Destructive variants focus
 * Cancel so Enter never destroys by accident.
 */
export function ActionDialog({
  open, title, description, children, comment, confirmLabel, cancelLabel = 'Cancel', tone = 'primary', busy, error, overline, size = 'md', focusCancel, onCancel, onConfirm,
}: ActionDialogProps) {
  const [text, setText] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const areaRef = useRef<HTMLTextAreaElement>(null)
  const cancelRef = useRef<HTMLButtonElement>(null)
  const formId = useId()
  const danger = tone === 'danger'
  const cancelFirst = danger || Boolean(focusCancel)

  useEffect(() => {
    if (open) {
      setText('')
      setProblem(null)
    }
  }, [open])

  // Destructive and irreversible confirmations start on Cancel. React's autoFocus does not render an [autofocus]
  // attribute, so the dialog's own initial focus (first field) would otherwise win; this runs after it.
  useEffect(() => {
    if (open && cancelFirst) cancelRef.current?.focus()
  }, [open, cancelFirst])

  function submit(event: FormEvent) {
    event.preventDefault()
    const value = text.trim()
    if (comment?.required && !value) {
      setProblem(comment.requiredMessage ?? 'Add a short reason. It is kept in the decision history.')
      areaRef.current?.focus()
      return
    }
    onConfirm(value)
  }

  function addSuggestion(reason: string) {
    setProblem(null)
    setText(current => (current.trim() ? `${current.trim()} ${reason}` : reason))
    areaRef.current?.focus()
  }

  return (
    <Dialog
      open={open}
      onClose={onCancel}
      title={title}
      description={description}
      busy={busy}
      alert={danger || focusCancel}
      overline={overline}
      size={size}
      footer={
        <>
          <Button ref={cancelRef} variant="secondary" onClick={onCancel} disabled={busy}>
            {cancelLabel}
          </Button>
          <Button type="submit" form={formId} variant={danger ? 'danger' : 'primary'} loading={busy}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <form id={formId} className="sx-action-form" onSubmit={submit} noValidate>
        {children}
        {comment && (
          <Field
            label={comment.label ?? 'Comment'}
            optional={!comment.required}
            hint={comment.hint}
            error={problem}
          >
            <Textarea
              ref={areaRef}
              rows={3}
              maxRows={8}
              maxLength={comment.maxLength ?? 1000}
              showCount={Boolean(comment.maxLength && comment.maxLength <= 1000)}
              value={text}
              placeholder={comment.placeholder}
              onChange={event => {
                setText(event.target.value)
                if (problem) setProblem(null)
              }}
              onKeyDown={event => {
                if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
                  event.preventDefault()
                  ;(event.currentTarget.form as HTMLFormElement | null)?.requestSubmit()
                }
              }}
            />
          </Field>
        )}
        {comment?.suggestions && comment.suggestions.length > 0 && (
          <div className="sx-suggestions" role="group" aria-label="Quick reasons">
            {comment.suggestions.map(reason => (
              <button key={reason} type="button" className="sx-suggestion" onClick={() => addSuggestion(reason)}>
                {reason}
              </button>
            ))}
          </div>
        )}
        <InlineError error={error} />
      </form>
    </Dialog>
  )
}
