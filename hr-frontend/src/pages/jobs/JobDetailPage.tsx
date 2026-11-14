import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { errorMessage } from '../../api/client'
import { jobsApi } from '../../api/jobs'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { JobStaff, JobStatus } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { RequirementTags, StatusBadge } from '../../components/Badges'
import { ConfirmDialog } from '../../components/Dialog'
import { DownloadIcon } from '../../components/Icons'
import { useNotices } from '../../components/Notices'
import { ErrorPanel, LoadingPanel } from '../../components/States'
import { Tabs } from '../../components/Tabs'
import { formatDate, formatDateTime, formatNumber, humanize } from '../../lib/format'
import { usePageTitle } from '../../lib/hooks'
import { useSearchState } from '../../lib/useSearchState'
import { ApplicantsTab } from './ApplicantsTab'
import { FairnessReportView } from './FairnessReportView'
import { InterviewKitTab } from './InterviewKitTab'
import { WorkSamplesTab } from './WorkSamplesTab'

type TabKey = 'applicants' | 'work-samples' | 'interview-kit' | 'fairness' | 'details'

type Transition = { to: JobStatus | 'DELETE'; label: string; title: string; message: string; danger?: boolean }

/** Job status transitions for the owner HR, HR manager or admin (plan 3.4). */
function transitionsFor(status: JobStatus): Transition[] {
  switch (status) {
    case 'DRAFT': return [
      { to: 'PUBLISHED', label: 'Publish', title: 'Publish this job?', message: 'Candidates can find and apply to it right away.' },
      { to: 'CANCELLED', label: 'Cancel draft', title: 'Cancel this draft?', message: 'Cancelled jobs cannot be reopened.', danger: true },
      { to: 'DELETE', label: 'Delete draft', title: 'Delete this draft?', message: 'The draft and its requirements are removed permanently.', danger: true },
    ]
    case 'PUBLISHED': return [
      { to: 'ON_HOLD', label: 'Put on hold', title: 'Put this job on hold?', message: 'It disappears from the job board; existing applicants stay in review.' },
      { to: 'CLOSED', label: 'Close job', title: 'Close this job?', message: 'Closed jobs stop taking applications and cannot be reopened.', danger: true },
    ]
    case 'ON_HOLD': return [
      { to: 'PUBLISHED', label: 'Resume publishing', title: 'Publish this job again?', message: 'Candidates can apply again.' },
      { to: 'CLOSED', label: 'Close job', title: 'Close this job?', message: 'Closed jobs stop taking applications and cannot be reopened.', danger: true },
    ]
    default: return []
  }
}

export function JobDetailPage() {
  const { jobId } = useParams()
  const id = Number(jobId)
  const job = useQuery({ queryKey: qk.job(id), queryFn: ({ signal }) => jobsApi.get(id, signal), enabled: Number.isInteger(id) })
  usePageTitle(job.data?.title ?? 'Job')

  if (job.isPending) return <LoadingPanel label="Loading job…" />
  if (job.isError) return <ErrorPanel error={job.error} title="We couldn’t load this job" onRetry={() => void job.refetch()} />
  return <JobDetail job={job.data} />
}

function JobDetail({ job }: { job: JobStaff }) {
  const { can } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const notices = useNotices()
  const state = useSearchState()
  const canManage = can('jobs.manage')
  const [pending, setPending] = useState<Transition | null>(null)

  const tabs: { key: TabKey; label: string; badge?: number }[] = [
    { key: 'applicants', label: 'Applicants', badge: job.applicationCount },
    { key: 'work-samples', label: 'Work samples' },
    { key: 'interview-kit', label: 'Interview kit' },
    ...(can('fairness.job') ? [{ key: 'fairness' as const, label: 'Fairness' }] : []),
    { key: 'details', label: 'Details' },
  ]
  const requested = state.get('tab', 'applicants') as TabKey
  const active: TabKey = tabs.some(tab => tab.key === requested) ? requested : 'applicants'

  const transition = useMutation({
    mutationFn: async (target: Transition) => {
      if (target.to === 'DELETE') {
        await jobsApi.remove(job.id)
        return null
      }
      return jobsApi.setStatus(job.id, target.to)
    },
    onSuccess: (updated, target) => {
      void queryClient.invalidateQueries({ queryKey: qk.jobs })
      setPending(null)
      if (!updated) {
        notices.success(`“${job.title}” was deleted.`)
        navigate('/jobs', { replace: true })
        return
      }
      queryClient.setQueryData(qk.job(job.id), updated)
      notices.success(`“${job.title}” is now ${humanize(target.to).toLowerCase()}.`)
    },
  })

  const exportAudit = useMutation({
    mutationFn: () => reviewApi.exportAudit(job.id),
    onSuccess: filename => notices.success(`Downloaded ${filename}.`),
    onError: cause => notices.error(errorMessage(cause, 'The audit export failed.')),
  })

  const editable = ['DRAFT', 'PUBLISHED', 'ON_HOLD'].includes(job.status)

  return (
    <>
      <Link className="back-link" to="/jobs">← Back to jobs</Link>
      <section className="detail-card job-header-card">
        <div className="detail-heading">
          <div>
            <p className="eyebrow">JOB #{job.id}{job.requirementsVersion ? ` · REQUIREMENTS V${job.requirementsVersion}` : ''}</p>
            <h1>{job.title}</h1>
            <div className="detail-tags">
              <span>{job.department}</span>
              <span>{job.location}</span>
              <span>{humanize(job.jobType)}</span>
              <span>{humanize(job.experienceLevel)}</span>
              {job.deadline && <span>Deadline {formatDateTime(job.deadline)}</span>}
            </div>
          </div>
          <StatusBadge status={job.status} />
        </div>
        {job.closedReason && <p className="muted-copy">Closed: {humanize(job.closedReason)}</p>}
        {(canManage || can('audit.jobExport')) && (
          <div className="detail-actions">
            {canManage && editable && <Link className="outline-button" to={`/jobs/${job.id}/edit`}>Edit job</Link>}
            {canManage && transitionsFor(job.status).map(item => (
              <button
                key={item.to}
                type="button"
                className={item.danger ? 'outline-button danger-outline' : item.to === 'PUBLISHED' ? 'primary-button workspace-primary' : 'outline-button'}
                onClick={() => { transition.reset(); setPending(item) }}
              >
                {item.label}
              </button>
            ))}
            {can('audit.jobExport') && (
              <button type="button" className="outline-button" onClick={() => exportAudit.mutate()} disabled={exportAudit.isPending}>
                <DownloadIcon /> {exportAudit.isPending ? 'Exporting…' : 'Export audit CSV'}
              </button>
            )}
          </div>
        )}
      </section>

      <Tabs items={tabs} active={active} onChange={key => state.set({ tab: key === 'applicants' ? null : key })} label="Job sections">
        {active === 'applicants' && <ApplicantsTab job={job} />}
        {active === 'work-samples' && <WorkSamplesTab job={job} />}
        {active === 'interview-kit' && <InterviewKitTab job={job} />}
        {active === 'fairness' && <JobFairness jobId={job.id} />}
        {active === 'details' && <JobDetails job={job} />}
      </Tabs>

      <ConfirmDialog
        open={pending != null}
        title={pending?.title ?? ''}
        message={pending?.message ?? ''}
        confirmLabel={pending?.label ?? 'Confirm'}
        danger={pending?.danger}
        busy={transition.isPending}
        error={transition.error}
        onCancel={() => setPending(null)}
        onConfirm={() => { if (pending) transition.mutate(pending) }}
      />
    </>
  )
}

function JobFairness({ jobId }: { jobId: number }) {
  const report = useQuery({ queryKey: qk.jobFairness(jobId), queryFn: ({ signal }) => reviewApi.fairness(jobId, signal) })
  if (report.isPending) return <LoadingPanel label="Calculating selection rates…" />
  if (report.isError) return <ErrorPanel error={report.error} title="We couldn’t load the fairness report" onRetry={() => void report.refetch()} />
  return <FairnessReportView report={report.data} />
}

function JobDetails({ job }: { job: JobStaff }) {
  const salary = job.minimumSalary == null && job.maximumSalary == null
    ? 'Not specified'
    : job.minimumSalary != null && job.maximumSalary != null
      ? `${formatNumber(job.minimumSalary)} – ${formatNumber(job.maximumSalary)}`
      : `${formatNumber(job.minimumSalary ?? job.maximumSalary)}+`
  return (
    <section className="detail-card">
      <div className="detail-columns">
        <div className="detail-main">
          <h2>About the role</h2>
          <p className="description-text">{job.description}</p>
          <h2>Requirements</h2>
          {job.requirements.length === 0 ? <p className="muted-copy">No requirements yet. Applicants cannot be ranked until requirements exist.</p> : (
            <ul className="requirement-list">
              {job.requirements.map(requirement => (
                <li key={requirement.id}>
                  <span className="requirement-bullet" aria-hidden="true">✓</span>
                  <span>
                    <strong>{humanize(requirement.type)}</strong> · {requirement.description}
                    {requirement.alternatives?.length > 0 && <small> (or {requirement.alternatives.join(', ')})</small>}
                    {requirement.proficiency && <small> · {humanize(requirement.proficiency)}</small>}
                  </span>
                  <RequirementTags mandatory={requirement.mandatory} knockout={requirement.knockout ?? requirement.mandatory} minimumYears={requirement.minimumYears} />
                </li>
              ))}
            </ul>
          )}
        </div>
        <aside className="detail-facts" aria-label="Role details">
          <h2>Role details</h2>
          <Fact label="Employment type" value={humanize(job.jobType)} />
          <Fact label="Experience level" value={humanize(job.experienceLevel)} />
          <Fact label="Salary range" value={salary} />
          <Fact label="Openings" value={String(job.vacancies)} />
          <Fact label="Application deadline" value={formatDateTime(job.deadline)} />
          <Fact label="Published" value={formatDate(job.publishedAt)} />
          <Fact label="Created by" value={job.createdByName || job.createdByEmail} />
          <Fact label="Last updated" value={formatDateTime(job.updatedAt)} />
        </aside>
      </div>
    </section>
  )
}

function Fact({ label, value }: { label: string; value: string }) {
  return <div className="fact-row"><span>{label}</span><strong>{value}</strong></div>
}
