import { useState } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import { JOB_STATUSES } from '../../api/types'
import type { AdminJob, AdminJobParams, JobStatus } from '../../api/types'
import { ActionDialog } from '../../components/ActionDialog'
import { QueryError } from '../../components/QueryError'
import { JobStatusBadge, jobStatusLabel } from '../../components/StatusBadges'
import { Button, DataTable, EmptyState, Input, PageHeader, Pagination, Radio, RadioGroup, Select, useToast, type Column } from '../../design/components'
import { BriefcaseBusiness, Icon, Search } from '../../design/icons'
import { formatNumber, plural, relativeTime } from '../../design/lib/format'
import { oneOf, useClampPage, useSearchState } from '../../lib/useSearchState'
import { useUrlSearch } from './shared'
import './admin.css'

/** Administrative override table (H7). CLOSED and CANCELLED are terminal even for administrators. */
export const OVERRIDE_TARGETS: Partial<Record<JobStatus, JobStatus[]>> = {
  PUBLISHED: ['ON_HOLD', 'CLOSED'],
  ON_HOLD: ['CLOSED'],
  DRAFT: ['CANCELLED'],
}

const CONSEQUENCE: Partial<Record<JobStatus, string>> = {
  ON_HOLD: 'Candidates can no longer apply; existing applications stay as they are.',
  CLOSED: 'Final. The job stops taking applications for good.',
  CANCELLED: 'Final. The draft is never published.',
}

/** /admin/jobs: every job in the organisation, with a reasoned status override where one is allowed. */
export function AdminJobsPage() {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const state = useSearchState()
  const search = useUrlSearch(state)
  const status = oneOf<JobStatus | ''>(state.get('status'), ['', ...JOB_STATUSES], '')
  const page = state.getPage()
  const params: AdminJobParams = { page, size: 20, status: status || undefined, search: search.value || undefined }
  const jobs = useQuery({ queryKey: qk.adminJobs(params), queryFn: ({ signal }) => adminApi.jobs(params, signal), placeholderData: keepPreviousData })
  useClampPage(state, page, jobs.isPlaceholderData ? undefined : jobs.data)
  const [target, setTarget] = useState<AdminJob | null>(null)
  const [next, setNext] = useState<JobStatus | null>(null)

  const override = useMutation({
    mutationFn: ({ job, to, reason }: { job: AdminJob; to: JobStatus; reason: string }) => adminApi.overrideJobStatus(job.id, to, reason),
    onSuccess: updated => {
      setTarget(null)
      toast({ tone: 'success', title: `“${updated.title}” is now ${jobStatusLabel(updated.status).toLowerCase()}`, body: 'The override and its reason are in the audit log.' })
      void queryClient.invalidateQueries({ queryKey: ['admin', 'jobs'] })
      void queryClient.invalidateQueries({ queryKey: qk.jobs })
    },
  })

  const columns: Column<AdminJob>[] = [
    { key: 'title', header: 'Job', primary: true, sortValue: j => j.title, cell: j => <span className="adm-person-text"><Link className="adm-strong" to={`/jobs/${j.id}`}>{j.title}</Link><span className="adm-faint">{j.department} · {j.location}</span></span> },
    { key: 'owner', header: 'Owner', hideWhenStacked: true, sortValue: j => j.createdByEmail ?? '', cell: j => <span className="adm-faint">{j.createdByEmail || '—'}</span> },
    { key: 'status', header: 'Status', width: '120px', sortValue: j => j.status, cell: j => <JobStatusBadge status={j.status} /> },
    { key: 'apps', header: 'Applications', numeric: true, width: '120px', sortValue: j => j.applicationCount, cell: j => formatNumber(j.applicationCount) },
    { key: 'created', header: 'Created', width: '120px', hideWhenStacked: true, sortValue: j => j.createdAt, cell: j => <time className="adm-faint" dateTime={j.createdAt}>{relativeTime(j.createdAt)}</time> },
    {
      key: 'act', header: <span className="sr-only">Override</span>, align: 'end', width: '150px',
      cell: j => (OVERRIDE_TARGETS[j.status]?.length
        ? <Button size="sm" variant="secondary" onClick={() => { override.reset(); setNext(OVERRIDE_TARGETS[j.status]![0]); setTarget(j) }}>Override status</Button>
        : <span className="adm-faint">Final</span>),
    },
  ]

  const targets = target ? OVERRIDE_TARGETS[target.status] ?? [] : []
  const filtered = Boolean(search.value || status)

  return (
    <div className="adm" data-width="wide">
      <PageHeader title="Job oversight" meta={[jobs.data ? plural(jobs.data.totalElements, 'job') : '…', 'Overrides need a reason and are audited']} />
      {jobs.isError && !jobs.data ? <QueryError error={jobs.error} title="We couldn’t load jobs" onRetry={() => void jobs.refetch()} retrying={jobs.isFetching} /> : (
        <DataTable
          caption="Jobs in the organisation"
          columns={columns}
          rows={jobs.data?.content ?? []}
          rowKey={j => j.id}
          loading={jobs.isPending}
          toolbar={
            <div className="adm-toolbar" role="search">
              <Input leading={<Icon icon={Search} size={14} />} type="search" aria-label="Search jobs" placeholder="Search jobs" value={search.input} onChange={e => search.setInput(e.target.value)} />
              <Select aria-label="Status" value={status} onChange={e => state.set({ status: e.target.value })}>
                <option value="">Any status</option>
                {JOB_STATUSES.map(s => <option key={s} value={s}>{jobStatusLabel(s)}</option>)}
              </Select>
            </div>
          }
          empty={<EmptyState compact icon={BriefcaseBusiness} title={filtered ? 'No jobs match these filters' : 'No jobs yet'} action={filtered ? <Button size="sm" variant="secondary" onClick={() => state.set({ q: null, status: null })}>Clear filters</Button> : undefined} />}
        />
      )}
      {jobs.data && jobs.data.totalElements > 20 && <Pagination page={page + 1} pageSize={20} total={jobs.data.totalElements} onPageChange={p => state.set({ page: p - 1 })} />}

      <ActionDialog
        open={target != null}
        title={`Override the status of “${target?.title ?? ''}”?`}
        description={`Currently ${target ? jobStatusLabel(target.status).toLowerCase() : ''}. The job’s owner sees the change and your reason.`}
        confirmLabel="Override status"
        tone={next === 'CLOSED' || next === 'CANCELLED' ? 'danger' : 'primary'}
        busy={override.isPending}
        error={override.error}
        comment={{ label: 'Reason', required: true, maxLength: 500, placeholder: 'Why this override is needed', requiredMessage: 'A reason is required for an override.' }}
        onCancel={() => setTarget(null)}
        onConfirm={reason => { if (target && next) override.mutate({ job: target, to: next, reason }) }}
      >
        <RadioGroup legend="New status" value={next ?? ''} onChange={v => setNext(v as JobStatus)}>
          {targets.map(s => <Radio key={s} value={s} label={jobStatusLabel(s)} description={CONSEQUENCE[s]} />)}
        </RadioGroup>
      </ActionDialog>
    </div>
  )
}
