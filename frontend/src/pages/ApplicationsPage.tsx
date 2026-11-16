import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { applicationsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { Application, ApplicationStatus } from '../api/types'
import { ErrorState } from '../components/Feedback'
import { Badge, DataTable, EmptyState, PageHeader, Pagination, buttonProps, type Tone } from '../design/components'
import { ArrowRight, CalendarClock, FileText, Icon, ListChecks } from '../design/icons'
import { formatDate, plural, relativeTime } from '../design/lib/format'
import { applicationStatusLabel } from '../lib/labels'
import { nextStepFor, tasksForJob, type NextStep } from '../lib/nextStep'
import { interviewsQuery, resumesQuery, workSamplesQuery } from '../lib/queries'
import './common.css'
import './applications/applications.css'

const PAGE_SIZE = 10

export const STATUS_TONE: Record<ApplicationStatus, { tone: Tone; variant?: 'solid' | 'outline' }> = {
  SUBMITTED: { tone: 'neutral' },
  SHORTLISTED: { tone: 'info' },
  INTERVIEWING: { tone: 'info' },
  SELECTED: { tone: 'success' },
  REJECTED: { tone: 'neutral' },
  WITHDRAWN: { tone: 'neutral', variant: 'outline' },
}

export function ApplicationStatusBadge({ status }: { status: string }) {
  const t = STATUS_TONE[status as ApplicationStatus] ?? { tone: 'neutral' as Tone }
  return (
    <Badge tone={t.tone} variant={t.variant ?? 'solid'}>
      {applicationStatusLabel(status)}
    </Badge>
  )
}

export function NextStepText({ step }: { step: NextStep }) {
  const icon = step.kind === 'work-sample' || step.kind === 'work-sample-started' ? ListChecks : step.kind === 'interview' ? CalendarClock : null
  const urgent = step.kind === 'work-sample' || step.kind === 'work-sample-started' || step.kind === 'interview'
  return (
    <span className="a-next" data-urgent={urgent || undefined}>
      {icon && <Icon icon={icon} size={14} />}
      {step.label}
    </span>
  )
}

/**
 * Applications (REDESIGN_PLAN A6): a table with a Next step column, whole rows open the detail (↑/↓/Enter work),
 * stacked rows on phones. The next step joins work samples and interviews by job title (API gap §14.1).
 */
export function ApplicationsPage() {
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0)
  const applications = useQuery({
    queryKey: queryKeys.applications(page),
    queryFn: () => applicationsApi.list(page, PAGE_SIZE),
    placeholderData: keepPreviousData,
  })
  const samples = useQuery(workSamplesQuery)
  const interviews = useQuery(interviewsQuery)
  const resumes = useQuery(resumesQuery)

  const rows = applications.data?.content ?? []
  const nextOf = (a: Application) => {
    const tasks = tasksForJob(a.jobTitle, samples.data, interviews.data)
    return nextStepFor(a, tasks.samples, tasks.interviews)
  }
  const total = applications.data?.totalElements ?? 0
  const noResume = resumes.data && resumes.data.length === 0

  return (
    <>
      <PageHeader
        title="Applications"
        meta={applications.data && total > 0 ? [plural(total, 'application')] : undefined}
        description="Follow each application and see exactly what happens next."
      />
      {applications.isError && !applications.data ? (
        <ErrorState error={applications.error} onRetry={() => void applications.refetch()} title="Your applications could not be loaded" />
      ) : (
        <DataTable<Application>
          caption="Your applications"
          rowKey={a => a.id}
          rows={rows}
          loading={applications.isPending}
          loadingRows={3}
          stickyHeader={false}
          onRowActivate={a => navigate(`/applications/${a.id}`)}
          className={applications.isPlaceholderData ? 'c-dim' : undefined}
          empty={
            <EmptyState
              icon={FileText}
              title="No applications yet"
              description={noResume ? 'Add your resume first, then apply to any open role in one dialog.' : 'When you apply to a role, it appears here with its next step.'}
              action={
                <Link {...buttonProps('primary')} to={noResume ? '/resumes' : '/jobs'}>
                  {noResume ? 'Add your resume' : 'Find jobs'}
                  <Icon icon={ArrowRight} size={16} />
                </Link>
              }
            />
          }
          columns={[
            {
              key: 'role',
              header: 'Role',
              primary: true,
              cell: a => (
                <span className="a-role">
                  <Link to={`/applications/${a.id}`} className="a-role-link">
                    {a.jobTitle}
                  </Link>
                  <span className="c-meta">{a.organizationName || '—'}</span>
                </span>
              ),
            },
            { key: 'status', header: 'Status', width: '140px', cell: a => <ApplicationStatusBadge status={a.status} /> },
            { key: 'next', header: 'Next step', cell: a => <NextStepText step={nextOf(a)} /> },
            {
              key: 'applied',
              header: 'Applied',
              width: '130px',
              hideWhenStacked: true,
              cell: a => (
                <time dateTime={a.appliedAt} title={formatDate(a.appliedAt, { dateStyle: 'medium' })} className="c-muted numeric">
                  {relativeTime(a.appliedAt)}
                </time>
              ),
            },
          ]}
        />
      )}
      {applications.data && total > PAGE_SIZE && (
        <Pagination
          className="j-pagination"
          page={page + 1}
          pageSize={PAGE_SIZE}
          total={total}
          onPageChange={next => setParams(next > 1 ? { page: String(next - 1) } : {})}
        />
      )}
    </>
  )
}
