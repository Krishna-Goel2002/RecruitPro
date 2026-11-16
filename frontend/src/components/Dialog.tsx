import type { ReactNode } from 'react'
import { Button, Dialog } from '../design/components'
import { FormError } from './Feedback'

interface ConfirmDialogProps {
  open: boolean
  /** Names the object and the consequence: "Withdraw your application for Senior Backend Engineer?" */
  title: string
  children: ReactNode
  confirmLabel: string
  onConfirm: () => void
  onCancel: () => void
  busy?: boolean
  tone?: 'danger' | 'primary'
  error?: unknown
}

/**
 * Yes/no confirmation on the design-system Dialog (§9.15). Destructive confirmations are alert dialogs with Cancel
 * first and focused, so a stray Enter never confirms. Busy blocks Escape and the backdrop.
 */
export function ConfirmDialog({ open, title, children, confirmLabel, onConfirm, onCancel, busy = false, tone = 'danger', error }: ConfirmDialogProps) {
  const danger = tone === 'danger'
  return (
    <Dialog
      open={open}
      onClose={onCancel}
      title={title}
      size="sm"
      busy={busy}
      alert={danger}
      footer={
        <>
          <Button variant="secondary" onClick={onCancel} disabled={busy} autoFocus={danger}>
            Cancel
          </Button>
          <Button variant={danger ? 'danger' : 'primary'} loading={busy} onClick={onConfirm} autoFocus={!danger}>
            {confirmLabel}
          </Button>
        </>
      }
    >
      <div className="c-confirm-body">{children}</div>
      <FormError error={error} />
    </Dialog>
  )
}
