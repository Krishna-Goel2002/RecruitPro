import { Link, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { applicationsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import { StatusBadge } from '../components/Badges'
import { EmptyState, ErrorBlock, LoadingBlock, Pagination } from '../components/Feedback'
import { PageHeader } from '../components/PageHeader'
import { formatDate } from '../lib/format'

const PAGE_SIZE = 10

export function ApplicationsPage() {
  const [params, setParams] = useSearchParams()
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0)
  const applications = useQuery({
    queryKey: queryKeys.applications(page),
    queryFn: () => applicationsApi.list(page, PAGE_SIZE),
    placeholderData: keepPreviousData,
  })

  return (
    <>
      <PageHeader
        eyebrow="CANDIDATE PORTAL"
        title="Your applications"
        description="Follow each application, see what the employer has verified and add evidence."
        actions={applications.data ? <span className="stat-pill">{applications.data.totalElements} total</span> : undefined}
      />
      {applications.isPending ? (
        <LoadingBlock label="Loading applications…" />
      ) : applications.isError ? (
        <ErrorBlock error={applications.error} onRetry={() => void applications.refetch()} />
      ) : applications.data.content.length === 0 ? (
        <EmptyState
          icon="▣"
          title="No applications yet"
          action={
            <Link className="quiet-button" to="/jobs">
              Browse jobs
            </Link>
          }
        >
          When you apply to a role, it appears here.
        </EmptyState>
      ) : (
        <ul className={`application-list plain-list${applications.isPlaceholderData ? ' is-stale' : ''}`}>
          {applications.data.content.map(application => (
            <li key={application.id} className="application-card">
              <div className="application-main">
                <div className="job-symbol" aria-hidden="true">
                  ▣
                </div>
                <div>
                  <h2 className="card-title">
                    <Link to={`/applications/${application.id}`} className="card-link">
                      {application.jobTitle}
                    </Link>
                  </h2>
                  <p className="muted">
                    {application.organizationName && (
                      <>
                        {application.organizationName} <span className="dot" aria-hidden="true">·</span>{' '}
                      </>
                    )}
                    Applied {formatDate(application.appliedAt)} <span className="dot" aria-hidden="true">·</span> Resume:{' '}
                    {application.resumeFilename}
                  </p>
                </div>
              </div>
              <div className="application-actions">
                <StatusBadge status={application.status} />
                <span className="muted view-hint" aria-hidden="true">
                  View →
                </span>
              </div>
            </li>
          ))}
        </ul>
      )}
      {applications.data && (
        <Pagination
          label="Application pages"
          page={page}
          totalPages={applications.data.totalPages}
          totalElements={applications.data.totalElements}
          disabled={applications.isFetching}
          onPageChange={next => setParams(next > 0 ? { page: String(next) } : {})}
        />
      )}
    </>
  )
}
