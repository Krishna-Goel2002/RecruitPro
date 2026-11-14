import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { adminApi } from '../../api/admin'
import { qk } from '../../api/queryKeys'
import { AdminJob, AdminJobParams, JOB_STATUSES, JobStatus } from '../../api/types'
import { StatusBadge } from '../../components/Badges'
import { TextPromptDialog } from '../../components/Dialog'
import { Field } from '../../components/Field'
import { SearchIcon } from '../../components/Icons'
import { PageHeader } from '../../components/Layout'
import { useNotices } from '../../components/Notices'
import { Pagination } from '../../components/Pagination'
import { EmptyPanel, ErrorPanel, LoadingPanel } from '../../components/States'
import { formatDate, formatNumber, humanize } from '../../lib/format'
import { useDebouncedValue, usePageTitle } from '../../lib/hooks'
import { useSearchState } from '../../lib/useSearchState'

/** Administrative override table (H7). CLOSED and CANCELLED are terminal even for administrators. */
export const OVERRIDE_TARGETS: Partial<Record<JobStatus, JobStatus[]>> = {
  PUBLISHED: ['ON_HOLD', 'CLOSED'],
  ON_HOLD: ['CLOSED'],
  DRAFT: ['CANCELLED'],
}

/** /admin/jobs — oversight of every job in the organisation, with reasoned status overrides. */
export function AdminJobsPage() {
  usePageTitle('Job oversight')
  const queryClient = useQueryClient()
  const notices = useNotices()
  const state = useSearchState()
  const status = state.get('status') as JobStatus | ''
  const search = state.get('q')
  const page = state.getPage()
  const [searchInput, setSearchInput] = useState(search)
  const debounced = useDebouncedValue(searchInput.trim(), 300)
  useEffect(() => { setSearchInput(search) }, [search])
  useEffect(() => {
    if (debounced !== search) state.set({ q: debounced }, { replace: true })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced])

  const params: AdminJobParams = { page, size: 20, status: status || undefined, search: search || undefined }
  const jobs = useQuery({ queryKey: qk.adminJobs(params), queryFn: ({ signal }) => adminApi.jobs(params, signal) })
  const [target, setTarget] = useState<AdminJob | null>(null)
  const [nextStatus, setNextStatus] = useState<JobStatus | ''>('')

  const override = useMutation({
    mutationFn: ({ job, to, reason }: { job: AdminJob; to: JobStatus; reason: string }) => adminApi.overrideJobStatus(job.id, to, reason),
    onSuccess: updated => {
      setTarget(null)
      void queryClient.invalidateQueries({ queryKey: ['admin', 'jobs'] })
      void queryClient.invalidateQueries({ queryKey: qk.jobs })
      notices.success(`“${updated.title}” is now ${humanize(updated.status).toLowerCase()}.`)
    },
  })

  const targets = target ? OVERRIDE_TARGETS[target.status] ?? [] : []

  return (
    <>
      <PageHeader eyebrow="ADMINISTRATION" title="Job oversight" subtitle="Every job in your organisation. Overrides need a reason and are audited; closed and cancelled jobs cannot be changed." />
      <section className="jobs-card" aria-labelledby="admin-jobs-heading">
        <div className="jobs-card-head">
          <div><h2 id="admin-jobs-heading">Jobs</h2></div>
          <span className="record-count" aria-live="polite">{jobs.data ? `${jobs.data.totalElements} jobs` : '—'}</span>
        </div>
        <div className="jobs-toolbar" role="search">
          <label className="search-field">
            <SearchIcon />
            <span className="sr-only">Search jobs</span>
            <input type="search" placeholder="Search job titles…" value={searchInput} onChange={event => setSearchInput(event.target.value)} />
          </label>
          <label className="status-filter">
            <span>Status</span>
            <select value={status} onChange={event => state.set({ status: event.target.value })}>
              <option value="">All statuses</option>
              {JOB_STATUSES.map(value => <option key={value} value={value}>{humanize(value)}</option>)}
            </select>
          </label>
        </div>
        {jobs.isPending ? <LoadingPanel label="Loading jobs…" /> : jobs.isError ? (
          <ErrorPanel error={jobs.error} onRetry={() => void jobs.refetch()} />
        ) : jobs.data.content.length === 0 ? <EmptyPanel title="No matching jobs" /> : (
          <>
            <div className="job-table-wrap">
              <table className="job-table">
                <thead>
                  <tr>
                    <th scope="col">JOB</th>
                    <th scope="col">OWNER</th>
                    <th scope="col">STATUS</th>
                    <th scope="col">APPLICATIONS</th>
                    <th scope="col">CREATED</th>
                    <th scope="col"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {jobs.data.content.map(job => {
                    const allowed = OVERRIDE_TARGETS[job.status] ?? []
                    return (
                      <tr key={job.id}>
                        <td><Link className="job-title-link" to={`/jobs/${job.id}`}>{job.title}</Link><span className="job-meta">{job.department} · {job.location}</span></td>
                        <td>{job.createdByEmail}</td>
                        <td><StatusBadge status={job.status} /></td>
                        <td>{formatNumber(job.applicationCount)}</td>
                        <td>{formatDate(job.createdAt)}</td>
                        <td>
                          {allowed.length > 0 ? (
                            <button type="button" className="outline-button" onClick={() => { override.reset(); setNextStatus(allowed[0]); setTarget(job) }}>Override status</button>
                          ) : <span className="muted-copy">Final</span>}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            <Pagination page={jobs.data} onPage={next => state.set({ page: next })} label="jobs" />
          </>
        )}
      </section>

      <TextPromptDialog
        open={target != null}
        title={target ? `Override status of “${target.title}”` : ''}
        message={target ? `Currently ${humanize(target.status).toLowerCase()}. The job’s owner is not asked; the reason is stored in the audit log.` : undefined}
        label="Reason"
        required
        maxLength={500}
        placeholder="e.g. Role frozen by finance on 8 Nov"
        confirmLabel={nextStatus ? `Set to ${humanize(nextStatus).toLowerCase()}` : 'Apply'}
        danger={nextStatus === 'CLOSED' || nextStatus === 'CANCELLED'}
        busy={override.isPending}
        error={override.error}
        onCancel={() => setTarget(null)}
        onSubmit={reason => { if (target && nextStatus) override.mutate({ job: target, to: nextStatus, reason }) }}
      >
        <Field label="New status" required>
          <select value={nextStatus} onChange={event => setNextStatus(event.target.value as JobStatus)}>
            {targets.map(value => <option key={value} value={value}>{humanize(value)}</option>)}
          </select>
        </Field>
      </TextPromptDialog>
    </>
  )
}
