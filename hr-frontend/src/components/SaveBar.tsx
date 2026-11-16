import type { ReactNode } from 'react'
import { Button } from '../design/components'
import { plural } from '../design/lib/format'
import './staff-ui.css'

/**
 * Sticky "unsaved changes" bar for settings forms (organisation, verification). It slides up only while the form is
 * dirty, so Save is never a greyed-out button at rest: it simply is not there until there is something to save.
 */
export function SaveBar({ changes, busy, onDiscard, onSave, saveLabel = 'Save changes', note, form }: {
  changes: number
  busy?: boolean
  onDiscard: () => void
  onSave?: () => void
  saveLabel?: string
  note?: ReactNode
  /** id of the form the Save button submits (instead of onSave). */
  form?: string
}) {
  const open = changes > 0
  return (
    <div className="sx-savebar" data-open={open || undefined} aria-hidden={!open || undefined} {...(open ? {} : ({ inert: '' } as Record<string, string>))}>
      <div className="sx-savebar-inner" role="region" aria-label="Unsaved changes">
        <p className="sx-savebar-text">
          <span className="sx-savebar-dot" aria-hidden="true" />
          <span>
            <strong>{open ? plural(changes, 'unsaved change') : 'No changes'}</strong>
            {note && <span className="sx-savebar-note"> · {note}</span>}
          </span>
        </p>
        <div className="sx-savebar-actions">
          <Button variant="ghost" onClick={onDiscard} disabled={busy}>Discard</Button>
          <Button variant="primary" type={form ? 'submit' : 'button'} form={form} onClick={onSave} loading={busy}>
            {saveLabel}
          </Button>
        </div>
      </div>
    </div>
  )
}
