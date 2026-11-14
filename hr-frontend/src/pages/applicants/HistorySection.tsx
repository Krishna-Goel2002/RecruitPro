import { FormEvent, useEffect, useId, useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { ApplicantDetail, DecisionEvent } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { useNotices } from '../../components/Notices'
import { FormError } from '../../components/States'
import { formatDateTime, humanize } from '../../lib/format'

const ACTION_TEXT: Record<string, string> = {
  SHORTLIST: 'Shortlisted',
  REJECT: 'Rejected',
  UNDO_SHORTLIST: 'Shortlist undone',
  REOPEN: 'Reopened',
  MOVE_TO_INTERVIEW: 'Moved to interview',
  SELECT: 'Selected',
  WITHDRAW: 'Withdrawn by candidate',
  COMMENT_UPDATED: 'Review note',
  IDENTITY_REVEALED: 'Identity revealed',
}

/** Latest review note (recruiters may comment) and the decision history, newest first. */
export function HistorySection({ jobId, detail }: { jobId: number; detail: ApplicantDetail }) {
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const notices = useNotices()
  const id = useId()
  const latestNote = [...detail.history].reverse().find(event => event.actionType === 'COMMENT_UPDATED')?.comment ?? ''
  const [note, setNote] = useState(latestNote)
  useEffect(() => { setNote(latestNote) }, [latestNote])

  const save = useMutation({
    mutationFn: () => reviewApi.comment(jobId, detail.applicationId, note.trim()),
    onSuccess: () => {
      notices.success('Review note saved.')
      void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, detail.applicationId) })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    save.mutate()
  }

  const history: DecisionEvent[] = [...detail.history].sort((a, b) => b.timestamp.localeCompare(a.timestamp))

  return (
    <section className="detail-card section-card" aria-labelledby="history-heading">
      {can('applicants.comment') && (
        <form className="review-note" onSubmit={submit}>
          <label htmlFor={`${id}-note`}><strong>Review note</strong> <span className="field-hint">Visible to your team; saved in the history.</span></label>
          <textarea id={`${id}-note`} rows={3} maxLength={2000} value={note} onChange={event => setNote(event.target.value)} placeholder="What stood out, what to probe next…" />
          <FormError error={save.error} />
          <button type="submit" className="outline-button" disabled={save.isPending || note.trim() === latestNote.trim()}>{save.isPending ? 'Saving…' : 'Save note'}</button>
        </form>
      )}

      <h2 id="history-heading">Decision history</h2>
      {history.length === 0 ? <p className="muted-copy">No decisions yet.</p> : (
        <ol className="history-list">
          {history.map(event => (
            <li key={event.id}>
              <div className="history-line">
                <strong>{ACTION_TEXT[event.actionType] ?? humanize(event.actionType)}</strong>
                {event.previousStatus !== event.newStatus && <span className="history-transition">{humanize(event.previousStatus)} → {humanize(event.newStatus)}</span>}
              </div>
              <span className="item-meta">{event.actorName} · {formatDateTime(event.timestamp)}{event.matchRunId ? ` · on match run #${event.matchRunId}` : ''}</span>
              {event.comment && <p className="history-comment">“{event.comment}”</p>}
            </li>
          ))}
        </ol>
      )}
    </section>
  )
}
