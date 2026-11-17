import { useState, type ReactNode } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router-dom'
import { errorMessage } from '../../api/client'
import { jobsApi } from '../../api/jobs'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { JobStaff, JobStatus, Page } from '../../api/types'
import { ActionDialog } from '../../components/ActionDialog'
import { useToast, type MenuItem } from '../../design/components'
import { formatDate, plural } from '../../design/lib/format'
import { Copy, Download, Link2, Pencil, Trash2, X } from '../../design/icons'
import { absoluteAppUrl } from '../../lib/appUrl'

export type JobAction = 'PUBLISH' | 'HOLD' | 'RESUME' | 'CLOSE' | 'CANCEL' | 'DELETE'

const TARGET: Record<Exclude<JobAction, 'DELETE'>, JobStatus> = {
  PUBLISH: 'PUBLISHED', HOLD: 'ON_HOLD', RESUME: 'PUBLISHED', CLOSE: 'CLOSED', CANCEL: 'CANCELLED',
}

/** What each status offers (handbook 02 §2.2). The server re-checks every transition. */
export function actionsFor(status: JobStatus): JobAction[] {
  switch (status) {
    case 'DRAFT': return ['PUBLISH', 'CANCEL', 'DELETE']
    case 'PUBLISHED': return ['HOLD', 'CLOSE']
    case 'ON_HOLD': return ['RESUME', 'CLOSE']
    default: return []
  }
}

export const isEditable = (status: JobStatus) => status === 'DRAFT' || status === 'PUBLISHED' || status === 'ON_HOLD'

export const ACTION_LABEL: Record<JobAction, string> = {
  PUBLISH: 'Publish', HOLD: 'Put on hold', RESUME: 'Resume publishing', CLOSE: 'Close job', CANCEL: 'Cancel draft', DELETE: 'Delete draft',
}

/** Hold and resume are reversible and internal: they happen at once with Undo. The rest are confirmed first. */
const IMMEDIATE: JobAction[] = ['HOLD', 'RESUME']

function confirmCopy(action: JobAction, job: JobStaff): { title: string; body: ReactNode; label: string; danger: boolean; keep?: string } {
  const reqs = job.requirements.length
  const gates = job.requirements.filter(r => r.mandatory && r.knockout).length
  switch (action) {
    case 'PUBLISH':
      return {
        title: `Publish “${job.title}”?`,
        label: 'Publish',
        danger: false,
        body: (
          <>
            <p>Candidates can find and apply to it right away.</p>
            <ul className="hra-check-list">
              <li data-ok={reqs > 0 || undefined}>{reqs > 0 ? `${plural(reqs, 'requirement')}, ${plural(gates, 'ranking gate')}` : 'No requirements yet: applicants cannot be ranked until you add them.'}</li>
              <li data-ok>{job.deadline ? `Closes automatically on ${formatDate(job.deadline)}` : 'No deadline: it stays open until you close it.'}</li>
            </ul>
          </>
        ),
      }
    case 'CLOSE':
      return { title: `Close “${job.title}”?`, label: 'Close job', danger: true, body: <p>It stops taking applications and cannot be reopened. Applicants already in review stay where they are.</p> }
    case 'CANCEL':
      return { title: `Cancel the draft “${job.title}”?`, label: 'Cancel draft', keep: 'Keep draft', danger: true, body: <p>Cancelled jobs cannot be reopened. Use Delete instead if you want it gone.</p> }
    case 'DELETE':
      return { title: `Delete the draft “${job.title}”?`, label: 'Delete draft', keep: 'Keep draft', danger: true, body: <p>The draft, its requirements, work samples and interview kit are removed permanently. This is recorded in the audit log.</p> }
    default:
      return { title: ACTION_LABEL[action], label: ACTION_LABEL[action], danger: false, body: null }
  }
}

/**
 * Job status changes for the list and the job page: optimistic for hold/resume (with Undo), confirmed for publish,
 * close, cancel and delete. Updates every cached copy of the job so the list row and header change in place.
 */
export function useJobActions() {
  const queryClient = useQueryClient()
  const navigate = useNavigate()
  const { toast } = useToast()
  const [pending, setPending] = useState<{ job: JobStaff; action: JobAction } | null>(null)

  const patchCaches = (job: JobStaff) => {
    queryClient.setQueryData(qk.job(job.id), (current: JobStaff | undefined) => (current ? { ...current, ...job } : current))
    queryClient.setQueriesData<Page<JobStaff>>({ queryKey: ['jobs', 'list'] }, page =>
      page ? { ...page, content: page.content.map(item => (item.id === job.id ? { ...item, status: job.status } : item)) } : page,
    )
  }

  const mutation = useMutation({
    mutationFn: async ({ job, action }: { job: JobStaff; action: JobAction }) => {
      if (action === 'DELETE') {
        await jobsApi.remove(job.id)
        return null
      }
      return jobsApi.setStatus(job.id, TARGET[action])
    },
    onMutate: ({ job, action }) => {
      if (action === 'DELETE') return
      patchCaches({ ...job, status: TARGET[action] })
    },
    onError: (cause, { job, action }) => {
      patchCaches(job)
      if (IMMEDIATE.includes(action)) toast({ tone: 'danger', title: `Could not ${ACTION_LABEL[action].toLowerCase()}`, body: errorMessage(cause) })
    },
    onSuccess: (updated, { job, action }) => {
      void queryClient.invalidateQueries({ queryKey: qk.jobs })
      setPending(null)
      if (!updated) {
        toast({ tone: 'success', title: `“${job.title}” was deleted` })
        navigate('/jobs', { replace: true })
        return
      }
      queryClient.setQueryData(qk.job(job.id), (current: JobStaff | undefined) => ({ ...(current ?? job), ...updated }))
      if (IMMEDIATE.includes(action)) {
        const undo: JobAction = action === 'HOLD' ? 'RESUME' : 'HOLD'
        toast({
          tone: 'success',
          title: action === 'HOLD' ? `“${job.title}” is on hold` : `“${job.title}” is published again`,
          body: action === 'HOLD' ? 'Candidates can no longer see or apply to it.' : 'Candidates can apply again.',
          action: { label: 'Undo', onClick: () => mutation.mutate({ job: updated, action: undo }) },
        })
      } else {
        toast({ tone: 'success', title: action === 'PUBLISH' ? `“${job.title}” is published` : `“${job.title}” is ${updated.status === 'CLOSED' ? 'closed' : 'cancelled'}` })
      }
    },
  })

  const run = (job: JobStaff, action: JobAction) => {
    if (IMMEDIATE.includes(action)) mutation.mutate({ job, action })
    else {
      mutation.reset()
      setPending({ job, action })
    }
  }

  const copy = pending ? confirmCopy(pending.action, pending.job) : null
  const dialog = (
    <ActionDialog
      open={pending != null}
      title={copy?.title ?? ''}
      confirmLabel={copy?.label ?? 'Confirm'}
      cancelLabel={copy?.keep}
      tone={copy?.danger ? 'danger' : 'primary'}
      size="sm"
      busy={mutation.isPending}
      error={mutation.error}
      onCancel={() => setPending(null)}
      onConfirm={() => { if (pending) mutation.mutate(pending) }}
    >
      {copy?.body}
    </ActionDialog>
  )

  const exportAudit = useMutation({
    mutationFn: (job: JobStaff) => reviewApi.exportAudit(job.id),
    onSuccess: filename => toast({ tone: 'success', title: `${filename} downloaded`, body: 'The export itself is recorded in the audit log.' }),
    onError: (cause, job) => toast({ tone: 'danger', title: 'The audit export failed', body: errorMessage(cause), action: { label: 'Try again', onClick: () => exportAudit.mutate(job) } }),
  })

  const duplicate = useMutation({
    mutationFn: (job: JobStaff) => jobsApi.duplicate(job.id),
    onSuccess: (copy, job) => {
      void queryClient.invalidateQueries({ queryKey: qk.jobs })
      queryClient.setQueryData(qk.job(copy.id), copy)
      toast({ tone: 'success', title: `Copied “${job.title}” as a draft`, body: 'Rename it and adjust the details before publishing.' })
      navigate(`/jobs/${copy.id}/edit`)
    },
    onError: (cause, job) => toast({ tone: 'danger', title: 'The job could not be copied', body: errorMessage(cause), action: { label: 'Try again', onClick: () => duplicate.mutate(job) } }),
  })

  /** "⋯" menu items for a job (list row or page header). `primary` actions shown as buttons are left out. */
  const menuItems = (job: JobStaff, opts: { canManage: boolean; canExport: boolean; exclude?: JobAction[]; includeEdit?: boolean }): MenuItem[] => {
    const items: MenuItem[] = []
    if (opts.canManage && opts.includeEdit && isEditable(job.status)) items.push({ label: 'Edit job', icon: Pencil, to: `/jobs/${job.id}/edit` })
    if (opts.canManage) {
      for (const action of actionsFor(job.status)) {
        if (opts.exclude?.includes(action)) continue
        items.push({
          label: ACTION_LABEL[action],
          icon: action === 'DELETE' ? Trash2 : action === 'CLOSE' || action === 'CANCEL' ? X : undefined,
          danger: action === 'CLOSE' || action === 'CANCEL' || action === 'DELETE',
          separated: (action === 'CLOSE' || action === 'CANCEL') && items.length > 0,
          onSelect: () => run(job, action),
        })
      }
    }
    if (opts.canManage) items.push({ label: 'Duplicate as draft', icon: Copy, disabled: duplicate.isPending, onSelect: () => duplicate.mutate(job), separated: items.length > 0 })
    if (opts.canExport) items.push({ label: 'Export audit CSV', icon: Download, onSelect: () => exportAudit.mutate(job), separated: items.length > 0 })
    items.push({
      label: 'Copy link',
      icon: Link2,
      onSelect: () => {
        const url = absoluteAppUrl(`/jobs/${job.id}`)
        const failed = () => toast({ tone: 'warning', title: 'Could not copy the link', body: url })
        if (!navigator.clipboard) failed()
        else void navigator.clipboard.writeText(url).then(() => toast({ tone: 'success', title: 'Link copied', duration: 3000 }), failed)
      },
    })
    return items
  }

  return { run, dialog, menuItems, busy: mutation.isPending, exportAudit, duplicate }
}
