import { useEffect, useState } from 'react'
import { useQueries, useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { jobsApi } from '../../api/jobs'
import { qk } from '../../api/queryKeys'
import { JOB_STATUSES, JobListParams, JobStatus } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { StatusBadge } from '../../components/Badges'
import { ArrowIcon, BriefcaseIcon, PlusIcon, SearchIcon } from '../../components/Icons'
import { PageHeader } from '../../components/Layout'
import { Pagination } from '../../components/Pagination'
import { EmptyPanel, ErrorPanel, LoadingPanel } from '../../components/States'
import { formatDate, formatNumber, humanize } from '../../lib/format'
import { useDebouncedValue, usePageTitle } from '../../lib/hooks'
import { useSearchState } from '../../lib/useSearchState'

const STAT_STATUSES: { key: JobStatus | 'ALL'; label: string; icon: string }[] = [
  { key: 'ALL', label: 'Total jobs', icon: 'all' },
  { key: 'PUBLISHED', label: 'Published', icon: 'published' },
  { key: 'DRAFT', label: 'Drafts', icon: 'draft' },
  { key: 'ON_HOLD', label: 'On hold', icon: 'hold' },
  { key: 'CLOSED', label: 'Closed', icon: 'closed' },
]

export function JobsListPage() {
  usePageTitle('Jobs')
  const { can } = useAuth()
  const canManage = can('jobs.manage')
  const state = useSearchState()
  const status = state.get('status') as JobStatus | ''
  const search = state.get('q')
  const page = state.getPage()

  const [searchInput, setSearchInput] = useState(search)
  const debounced = useDebouncedValue(searchInput.trim(), 300)
  useEffect(() => { setSearchInput(search) }, [search])
  useEffect(() => {
    if (debounced !== search) state.set({ q: debounced }, { replace: true })
    // only react to the debounced input
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced])

  const params: JobListParams = { page, size: 20, status: status || undefined, search: search || undefined, sort: 'createdAt', direction: 'DESC' }
  const list = useQuery({ queryKey: qk.jobList(params), queryFn: ({ signal }) => jobsApi.list(params, signal) })

  const stats = useQueries({
    queries: STAT_STATUSES.map(stat => ({
      queryKey: qk.jobStats(stat.key),
      queryFn: ({ signal }: { signal: AbortSignal }) => jobsApi.list({ page: 0, size: 1, status: stat.key === 'ALL' ? undefined : stat.key }, signal),
      staleTime: 30_000,
    })),
  })

  const filtered = Boolean(search || status)

  return (
    <>
      <PageHeader
        eyebrow="HIRING OPERATIONS"
        title="Jobs"
        subtitle="Create roles, define what evidence matters, and review applicants blind-first."
        actions={canManage && <Link className="primary-button workspace-primary" to="/jobs/new"><PlusIcon /> Create job</Link>}
      />

      <section className="stat-grid five" aria-label="Job summary">
        {STAT_STATUSES.map((stat, index) => (
          <article className="stat-card" key={stat.key}>
            <span className={`stat-icon ${stat.icon}`} aria-hidden="true"><BriefcaseIcon /></span>
            <div>
              <span className="stat-label">{stat.label}</span>
              <strong className="stat-value">{stats[index].data ? formatNumber(stats[index].data.totalElements) : '—'}</strong>
            </div>
          </article>
        ))}
      </section>

      <section className="jobs-card" aria-labelledby="jobs-heading">
        <div className="jobs-card-head">
          <div>
            <h2 id="jobs-heading">All jobs</h2>
            <p>Newest first. Search matches job titles.</p>
          </div>
          <span className="record-count" aria-live="polite">{list.data ? `${list.data.totalElements} jobs` : '—'}</span>
        </div>
        <div className="jobs-toolbar" role="search">
          <label className="search-field">
            <SearchIcon />
            <span className="sr-only">Search jobs by title</span>
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

        {list.isPending ? (
          <LoadingPanel label="Loading jobs…" />
        ) : list.isError ? (
          <ErrorPanel error={list.error} title="We couldn’t load jobs" onRetry={() => void list.refetch()} />
        ) : list.data.content.length === 0 ? (
          <EmptyPanel
            title={filtered ? 'No matching jobs' : 'No jobs yet'}
            action={canManage && !filtered ? <Link className="outline-button" to="/jobs/new"><PlusIcon /> Create a job</Link> : undefined}
          >
            {filtered ? 'Try another title or status.' : canManage ? 'Create your first job draft to get started.' : 'Jobs created by your HR managers appear here.'}
          </EmptyPanel>
        ) : (
          <>
            <div className="job-table-wrap">
              <table className="job-table">
                <caption className="sr-only">Jobs, page {list.data.page + 1}</caption>
                <thead>
                  <tr>
                    <th scope="col">JOB TITLE</th>
                    <th scope="col">DEPARTMENT</th>
                    <th scope="col">LOCATION</th>
                    <th scope="col">STATUS</th>
                    <th scope="col">APPLICANTS</th>
                    <th scope="col">CREATED</th>
                    <th scope="col"><span className="sr-only">Actions</span></th>
                  </tr>
                </thead>
                <tbody>
                  {list.data.content.map(job => (
                    <tr key={job.id}>
                      <td>
                        <Link className="job-title-link" to={`/jobs/${job.id}`}>{job.title}</Link>
                        <span className="job-meta">{humanize(job.jobType)} · {humanize(job.experienceLevel)}</span>
                      </td>
                      <td>{job.department}</td>
                      <td>{job.location}</td>
                      <td><StatusBadge status={job.status} /></td>
                      <td>{job.applicationCount != null ? formatNumber(job.applicationCount) : '—'}</td>
                      <td>{formatDate(job.createdAt)}</td>
                      <td><Link className="row-action" to={`/jobs/${job.id}`} aria-label={`Open ${job.title}`}>Open <ArrowIcon /></Link></td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pagination page={list.data} onPage={next => state.set({ page: next })} label="jobs" />
          </>
        )}
      </section>
    </>
  )
}
