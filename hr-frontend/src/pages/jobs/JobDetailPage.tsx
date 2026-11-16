import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { isApiError } from '../../api/client'
import { workSamplesApi } from '../../api/assessments'
import { jobsApi } from '../../api/jobs'
import { qk } from '../../api/queryKeys'
import type { JobStaff } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { QueryError } from '../../components/QueryError'
import { JobStatusBadge } from '../../components/StatusBadges'
import {
  Alert, Button, EmptyState, IconButton, LoadingRegion, Menu, PageHeader, Skeleton, TabPanel, Tabs, Tag, buttonProps,
} from '../../design/components'
import { formatDate, plural, relativeTime } from '../../design/lib/format'
import { BriefcaseBusiness, Ellipsis, Icon, Pencil } from '../../design/icons'
import { humanize } from '../../lib/format'
import { useSearchState } from '../../lib/useSearchState'
import { ApplicantsTab } from './ApplicantsTab'
import { JobDetailsTab } from './JobDetailsTab'
import { JobFairnessTab } from './FairnessReportView'
import { fetchKit, InterviewKitTab } from './InterviewKitTab'
import { WorkSamplesTab } from './WorkSamplesTab'
import { isEditable, useJobActions, type JobAction } from './jobActions'
import './jobs.css'

type TabKey = 'applicants' | 'work-samples' | 'interview-kit' | 'fairness' | 'details'

const CLOSED_REASON: Record<string, string> = {
  DEADLINE_PASSED: 'Closed automatically when the deadline passed.',
  CLOSED_BY_HR: 'Closed by your team.',
  ADMIN_OVERRIDE: 'Closed by an administrator (status override).',
}

export function JobDetailPage() {
  const { jobId } = useParams()
  const id = Number(jobId)
  const job = useQuery({ queryKey: qk.job(id), queryFn: ({ signal }) => jobsApi.get(id, signal), enabled: Number.isInteger(id) && id > 0 })

  if (!Number.isInteger(id) || id <= 0 || isApiError(job.error, 404)) {
    return (
      <div data-width="wide">
        <PageHeader title="Job not found" breadcrumb={[{ label: 'Jobs', to: '/jobs' }, { label: 'Not found' }]} />
        <EmptyState icon={BriefcaseBusiness} title="This job does not exist, or it belongs to another organisation" description="The link may be out of date." action={<Link {...buttonProps('secondary')} to="/jobs">Back to jobs</Link>} />
      </div>
    )
  }
  if (job.isPending) return <JobHeaderSkeleton />
  if (job.isError) {
    return (
      <div data-width="wide">
        <PageHeader title="Job" breadcrumb={[{ label: 'Jobs', to: '/jobs' }, { label: 'Job' }]} />
        <QueryError error={job.error} title="This job could not be loaded" onRetry={() => void job.refetch()} retrying={job.isFetching} />
      </div>
    )
  }
  return <JobDetail job={job.data} />
}

function JobHeaderSkeleton() {
  return (
    <LoadingRegion label="Loading job" className="hra-job-skeleton">
      <Skeleton width={120} height={10} />
      <Skeleton width="42%" height={22} />
      <Skeleton width="56%" height={10} />
      <div className="hra-skel-tabs">{[88, 104, 96, 72, 64].map((w, i) => <Skeleton key={i} width={w} height={12} />)}</div>
      {Array.from({ length: 8 }, (_, i) => <Skeleton key={i} width="100%" height={20} />)}
    </LoadingRegion>
  )
}

function closesText(job: JobStaff): string | null {
  if (!job.deadline || job.status === 'CLOSED' || job.status === 'CANCELLED') return null
  const ms = new Date(job.deadline).getTime() - Date.now()
  return ms <= 0 ? 'Deadline passed' : `Closes ${relativeTime(job.deadline)} (${formatDate(job.deadline)})`
}

function JobDetail({ job }: { job: JobStaff }) {
  const { can } = useAuth()
  const state = useSearchState()
  const canManage = can('jobs.manage')
  const actions = useJobActions()

  // Counts for the tab labels (the lists are cached for the tabs themselves).
  const samples = useQuery({ queryKey: qk.workSamples(job.id), queryFn: ({ signal }) => workSamplesApi.list(job.id, signal) })
  const kit = useQuery({ queryKey: qk.interviewKit(job.id), queryFn: ({ signal }) => fetchKit(job.id, signal) })

  const tabs: { value: TabKey; label: string; count?: number }[] = [
    { value: 'applicants', label: 'Applicants', count: job.applicationCount },
    { value: 'work-samples', label: 'Work samples', count: samples.data?.length },
    { value: 'interview-kit', label: 'Interview kit', count: kit.data ? kit.data.questions.length : undefined },
    ...(can('fairness.job') ? [{ value: 'fairness' as const, label: 'Fairness' }] : []),
    { value: 'details', label: 'Details' },
  ]
  const requested = state.get('tab', 'applicants') as TabKey
  const active: TabKey = tabs.some(tab => tab.value === requested) ? requested : 'applicants'

  // At most two visible actions: Edit and the one status action people take next; the rest live in "⋯".
  const primary: JobAction | null = canManage ? (job.status === 'DRAFT' ? 'PUBLISH' : job.status === 'ON_HOLD' ? 'RESUME' : job.status === 'PUBLISHED' ? 'HOLD' : null) : null
  const menu = actions.menuItems(job, { canManage, canExport: can('audit.jobExport'), exclude: primary ? [primary] : [] })

  const meta = [
    job.department,
    job.location,
    `${humanize(job.jobType)} · ${humanize(job.experienceLevel)}`,
    closesText(job),
    plural(job.vacancies, 'opening'),
  ].filter(Boolean) as string[]

  return (
    <div className="hra-job" data-width="wide">
      <PageHeader
        documentTitle={job.title}
        breadcrumb={[{ label: 'Jobs', to: '/jobs' }, { label: job.title }]}
        title={job.title}
        status={
          <span className="hra-title-tags">
            <JobStatusBadge status={job.status} />
            {job.requirementsVersion != null && job.requirementsVersion > 1 && (
              <Tag title="Requirements change version when edited on a live job; decisions record the version they were made on.">Requirements v{job.requirementsVersion}</Tag>
            )}
          </span>
        }
        meta={meta}
        actions={
          <div className="hra-header-actions">
            {canManage && isEditable(job.status) && (
              <Link {...buttonProps('secondary')} to={`/jobs/${job.id}/edit${job.status === 'DRAFT' ? '?step=requirements' : ''}`}>
                <Icon icon={Pencil} size={16} /><span className="ui-btn-label">Edit</span>
              </Link>
            )}
            {primary && (
              <Button variant={primary === 'HOLD' ? 'secondary' : 'primary'} onClick={() => actions.run(job, primary)} loading={actions.busy}>
                {primary === 'PUBLISH' ? 'Publish' : primary === 'RESUME' ? 'Resume publishing' : 'Put on hold'}
              </Button>
            )}
            {menu.length > 0 && (
              <Menu label="More job actions" items={menu} trigger={props => <IconButton {...props} label="More job actions" icon={Ellipsis} variant="secondary" noTooltip />} />
            )}
          </div>
        }
      />

      {job.status === 'DRAFT' && (
        <Alert tone="info" className="hra-job-banner" action={canManage ? <Link className="hra-inline-link" to={`/jobs/${job.id}/edit?step=review`}>Review before publishing</Link> : undefined}>
          This job is a draft. Candidates cannot see it yet.
        </Alert>
      )}
      {job.status === 'ON_HOLD' && <Alert tone="warning" className="hra-job-banner">On hold: candidates cannot see or apply to this job. Applicants already here stay in review.</Alert>}
      {job.closedReason && <Alert tone="neutral" className="hra-job-banner">{CLOSED_REASON[job.closedReason] ?? `Closed: ${humanize(job.closedReason)}`}</Alert>}

      <Tabs
        label="Job sections"
        idBase="job"
        items={tabs}
        value={active}
        onChange={key => state.set({ tab: key === 'applicants' ? null : key, status: null, sort: null, peek: null, compare: null, from: null, to: null })}
        className="hra-job-tabs"
      />
      <TabPanel idBase="job" value="applicants" active={active} className="hra-tabpanel"><ApplicantsTab job={job} /></TabPanel>
      <TabPanel idBase="job" value="work-samples" active={active} className="hra-tabpanel"><WorkSamplesTab job={job} /></TabPanel>
      <TabPanel idBase="job" value="interview-kit" active={active} className="hra-tabpanel"><InterviewKitTab job={job} /></TabPanel>
      <TabPanel idBase="job" value="fairness" active={active} className="hra-tabpanel"><JobFairnessTab jobId={job.id} /></TabPanel>
      <TabPanel idBase="job" value="details" active={active} className="hra-tabpanel"><JobDetailsTab job={job} /></TabPanel>
      {actions.dialog}
    </div>
  )
}
