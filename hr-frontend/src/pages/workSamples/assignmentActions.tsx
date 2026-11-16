import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { workSamplesApi } from '../../api/assessments'
import { errorMessage } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { CANCELLABLE_ASSIGNMENT_STATUSES } from '../../api/types'
import type { WorkSampleAssignment, WorkSampleFile } from '../../api/types'
import { ActionDialog } from '../../components/ActionDialog'
import { useToast } from '../../design/components'

/*
 * Assignment actions shared by the applicant's Work samples tab and the job's Work samples tab (assignments table):
 * cancel with a required reason (ADMIN, HR_MANAGER; the candidate is told) and the audited download of a submitted file
 * (ADMIN, HR_MANAGER, RECRUITER). Both call the per-assignment endpoints, so they work from either page.
 *
 * Blind-first: assignments only exist for shortlisted or interviewing applications, and shortlisting reveals the
 * identity, so the candidate's own file names are never shown to a reviewer before reveal.
 */

export const CANCEL_REASON_MAX = 1000

export function canCancelAssignment(a: Pick<WorkSampleAssignment, 'status'>): boolean {
  return CANCELLABLE_ASSIGNMENT_STATUSES.includes(a.status)
}

/** Every cached view that shows this assignment: the job's assignment lists, the applicant page, the ranking. */
export function invalidateAssignment(queryClient: ReturnType<typeof useQueryClient>, jobId: number, applicationId?: number) {
  void queryClient.invalidateQueries({ queryKey: ['workSamples', jobId, 'assignments'] })
  if (applicationId != null) void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, applicationId) })
  void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
}

/**
 * Cancel an assignment: `request(assignment)` opens the confirmation (reason required, Cancel focused first);
 * `dialog` must be rendered once by the caller.
 */
export function useCancelAssignment(jobId: number, onCancelled?: (updated: WorkSampleAssignment) => void) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [target, setTarget] = useState<WorkSampleAssignment | null>(null)
  const cancel = useMutation({
    mutationFn: ({ id, reason }: { id: number; reason: string }) => workSamplesApi.cancel(id, reason),
    onSuccess: (updated, { id }) => {
      setTarget(null)
      toast({ tone: 'success', title: 'Assignment cancelled', body: 'The candidate is told it is no longer needed. Recorded in the audit log.' })
      invalidateAssignment(queryClient, jobId, updated?.applicationId ?? target?.applicationId)
      onCancelled?.(updated ?? { ...(target as WorkSampleAssignment), id, status: 'CANCELLED' })
    },
  })

  const request = (assignment: WorkSampleAssignment) => {
    cancel.reset()
    setTarget(assignment)
  }

  const dialog = (
    <ActionDialog
      open={target != null}
      title="Cancel this assignment?"
      description={target
        ? `“${target.workSampleTitle}” for application #A-${target.applicationId}. The candidate is told the work sample is no longer needed. A submission and its files are kept for the record.`
        : undefined}
      tone="danger"
      confirmLabel="Cancel assignment"
      busy={cancel.isPending}
      error={cancel.error}
      comment={{ label: 'Reason', required: true, maxLength: CANCEL_REASON_MAX, placeholder: 'Why it is no longer needed', requiredMessage: 'A reason is required to cancel.', hint: 'Sent to the candidate and kept in the audit log.' }}
      onCancel={() => setTarget(null)}
      onConfirm={reason => { if (target) cancel.mutate({ id: target.id, reason }) }}
    />
  )

  return { request, dialog, pending: cancel.isPending }
}

/** Audited download of one submitted file; the server names the file (Content-Disposition). */
export function useSubmissionDownload() {
  const { toast } = useToast()
  const download = useMutation({
    mutationFn: ({ assignmentId, file }: { assignmentId: number; file: WorkSampleFile }) =>
      workSamplesApi.downloadFile(assignmentId, file.id, file.originalName || `work-sample-${assignmentId}-file-${file.id}`),
    onSuccess: filename => toast({ tone: 'success', title: `Downloaded ${filename}`, body: 'Recorded in the audit log.' }),
    onError: cause => toast({ tone: 'danger', title: 'The download failed', body: errorMessage(cause) }),
  })
  return {
    start: (assignmentId: number, file: WorkSampleFile) => download.mutate({ assignmentId, file }),
    busyFileId: download.isPending ? download.variables?.file.id ?? null : null,
  }
}
