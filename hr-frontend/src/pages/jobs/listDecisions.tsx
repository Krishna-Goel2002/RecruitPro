import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { errorMessage } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { ApplicantDetail, ApplicantSummary, ApplicationStatus, JobStaff } from '../../api/types'
import { ActionDialog } from '../../components/ActionDialog'
import { useToast } from '../../design/components'
import { plural } from '../../design/lib/format'
import { useDeferredCommit } from '../../lib/deferredCommit'
import { canListDecide } from './ranking'

type ListAction = 'SHORTLIST' | 'REJECT'
type Override = { status: ApplicationStatus; pending: boolean }

const REJECT_REASONS = ['Must-have not evidenced after review.', 'Another applicant was a closer match.', 'Role requirements changed.']

/**
 * Shortlist / Not progressed from the ranking (one applicant or a selection). Confirm first — shortlisting reveals the
 * identity and every decision notifies the candidate — then the rows change at once and the requests are sent after
 * a 5-second Undo window (no server call, no audit entry if undone). API gap: no bulk endpoint, so a selection sends
 * one decision per applicant, in order.
 */
export function useListDecisions(job: JobStaff) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const schedule = useDeferredCommit()
  const [overrides, setOverrides] = useState<Map<number, Override>>(new Map())
  const [pending, setPending] = useState<{ action: ListAction; rows: ApplicantSummary[]; skipped: number } | null>(null)

  const patch = (ids: number[], value: Override | null) =>
    setOverrides(current => {
      const next = new Map(current)
      ids.forEach(id => (value ? next.set(id, value) : next.delete(id)))
      return next
    })

  const request = (action: ListAction, rows: ApplicantSummary[], details: Map<number, ApplicantDetail>) => {
    const eligible = rows.filter(row => canListDecide(action, row.applicationStatus, details.get(row.applicationId)?.allowedActions))
    if (!eligible.length) {
      toast({
        tone: 'info',
        title: action === 'SHORTLIST' ? 'Nothing to shortlist' : 'Nothing to mark as not progressed',
        body: action === 'SHORTLIST' ? 'Only submitted applications can be shortlisted.' : 'This application is already decided.',
        duration: 4000,
      })
      return
    }
    setPending({ action, rows: eligible, skipped: rows.length - eligible.length })
  }

  const confirm = (comment: string) => {
    if (!pending) return
    const { action, rows } = pending
    const ids = rows.map(row => row.applicationId)
    const target: ApplicationStatus = action === 'SHORTLIST' ? 'SHORTLISTED' : 'REJECTED'
    setPending(null)
    patch(ids, { status: target, pending: true })
    const who = rows.length === 1 ? rows[0].displayName : plural(rows.length, 'applicant')
    schedule({
      title: action === 'SHORTLIST' ? `Shortlisting ${who}` : `Marking ${who} as not progressed`,
      commit: async () => {
        const failures: { row: ApplicantSummary; cause: unknown }[] = []
        for (const row of rows) {
          try {
            await reviewApi.decide(job.id, row.applicationId, { action, comment: comment || undefined })
            patch([row.applicationId], { status: target, pending: false })
          } catch (cause) {
            failures.push({ row, cause })
            patch([row.applicationId], null)
          }
        }
        void queryClient.invalidateQueries({ queryKey: qk.applicants(job.id) })
        void queryClient.invalidateQueries({ queryKey: qk.job(job.id) })
        if (failures.length) {
          toast({
            tone: 'danger',
            title: failures.length === rows.length ? 'The decision was not saved' : `${plural(failures.length, 'decision')} not saved`,
            body: `${failures.map(f => f.row.displayName).join(', ')}: ${errorMessage(failures[0].cause)}`,
          })
        } else {
          toast({
            tone: 'success',
            title: action === 'SHORTLIST' ? `${who} shortlisted` : `${who} marked as not progressed`,
            body: action === 'SHORTLIST' ? 'Identity revealed to everyone reviewing this job; recorded in the audit log.' : 'The candidate is notified. You can reopen it from the applicant page.',
            duration: 5000,
          })
        }
        // Keep the optimistic status until the refetched list shows the server's answer.
        window.setTimeout(() => patch(ids, null), 4000)
      },
      revert: () => patch(ids, null),
    })
  }

  const shortlisting = pending?.action === 'SHORTLIST'
  const count = pending?.rows.length ?? 0
  const dialog = (
    <ActionDialog
      open={pending != null}
      size="sm"
      title={shortlisting
        ? count === 1 ? `Shortlist ${pending?.rows[0].displayName}?` : `Shortlist ${count} applicants?`
        : count === 1 ? `Mark ${pending?.rows[0].displayName} as not progressed?` : `Mark ${count} applicants as not progressed?`}
      confirmLabel={shortlisting ? (count === 1 ? 'Shortlist and reveal' : `Shortlist ${count}`) : 'Not progressed'}
      tone={shortlisting ? 'primary' : 'danger'}
      focusCancel={shortlisting}
      comment={{
        label: 'Comment',
        hint: 'Kept in the decision history with the match run it was made on.',
        maxLength: 1000,
        suggestions: shortlisting ? undefined : REJECT_REASONS,
      }}
      onCancel={() => setPending(null)}
      onConfirm={confirm}
    >
      {shortlisting ? (
        <div className="hra-dialog-facts">
          <p>Shortlisting reveals the identity to everyone reviewing this job:</p>
          <ul className="hra-check-list">
            <li data-ok>Name and contact details</li>
            <li data-ok>Resume file and cover letter</li>
            <li data-ok>Claim evidence links and verification sources</li>
          </ul>
          <p className="subtle">The reveal is recorded in the audit log with your name. You have 5 seconds to undo before anything is sent.</p>
        </div>
      ) : (
        <div className="hra-dialog-facts">
          <p>{count === 1 ? 'The candidate is notified' : 'Each candidate is notified'} that the application was not progressed. You can reopen it later from the applicant page, with a reason.</p>
          <p className="subtle">You have 5 seconds to undo before anything is sent.</p>
        </div>
      )}
      {pending && pending.skipped > 0 && <p className="subtle">{plural(pending.skipped, 'selected applicant')} {pending.skipped === 1 ? 'is' : 'are'} left out: already decided.</p>}
    </ActionDialog>
  )

  return { overrides, request, dialog }
}
