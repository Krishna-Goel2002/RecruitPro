import { useEffect, useMemo, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { jobsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { JobPublic, JobSearchParams, JobType } from '../api/types'
import { EmptyState, ErrorBlock, LoadingBlock, Pagination, Spinner } from '../components/Feedback'
import { Field } from '../components/Form'
import { PageHeader } from '../components/PageHeader'
import { formatDate, formatSalaryRange } from '../lib/format'
import { useDebouncedValue } from '../lib/hooks'
import { JOB_TYPE_OPTIONS, experienceLabel, jobTypeLabel } from '../lib/labels'

const PAGE_SIZE = 10
const DEBOUNCE_MS = 300

interface Filters {
  search: string
  department: string
  location: string
  jobType: JobType | ''
}

function readFilters(params: URLSearchParams): Filters {
  const jobType = params.get('jobType') ?? ''
  return {
    search: params.get('search') ?? '',
    department: params.get('department') ?? '',
    location: params.get('location') ?? '',
    jobType: JOB_TYPE_OPTIONS.some(option => option.value === jobType) ? (jobType as JobType) : '',
  }
}

function sameFilters(a: Filters, b: Filters): boolean {
  return a.search === b.search && a.department === b.department && a.location === b.location && a.jobType === b.jobType
}

function writeParams(filters: Filters, page: number): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.search.trim()) params.set('search', filters.search.trim())
  if (filters.department.trim()) params.set('department', filters.department.trim())
  if (filters.location.trim()) params.set('location', filters.location.trim())
  if (filters.jobType) params.set('jobType', filters.jobType)
  if (page > 0) params.set('page', String(page))
  return params
}

export function JobsPage() {
  const [params, setParams] = useSearchParams()
  const applied = useMemo(() => readFilters(params), [params])
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0)

  // The filter inputs stay mounted; typing updates local state, and the URL (and query) follow after 300 ms.
  const [inputs, setInputs] = useState<Filters>(applied)
  const debounced = useDebouncedValue(inputs, DEBOUNCE_MS)

  useEffect(() => {
    if (!sameFilters(debounced, readFilters(params))) setParams(writeParams(debounced, 0), { replace: true })
    // Only the debounced input triggers a URL update; `params` is read at that moment on purpose.
  }, [debounced])

  const searchParams: JobSearchParams = { page, size: PAGE_SIZE, ...applied }
  const jobs = useQuery({
    queryKey: queryKeys.jobs(searchParams),
    queryFn: () => jobsApi.search(searchParams),
    placeholderData: keepPreviousData,
  })

  const typing = !sameFilters(inputs, applied)
  const updating = jobs.isFetching && jobs.isPlaceholderData
  const hasFilters = Boolean(inputs.search || inputs.department || inputs.location || inputs.jobType)
  const set = (key: keyof Filters) => (value: string) => setInputs(current => ({ ...current, [key]: value }))

  return (
    <>
      <PageHeader eyebrow="CANDIDATE PORTAL" title="Find your next role" description="Published roles from every organisation on RecruitPro." />

      <form className="job-filters" role="search" aria-label="Filter jobs" onSubmit={event => event.preventDefault()}>
        <Field label="Job title or keyword">
          {control => (
            <input {...control} type="search" value={inputs.search} placeholder="e.g. Accountant" onChange={event => set('search')(event.target.value)} />
          )}
        </Field>
        <Field label="Department">
          {control => <input {...control} value={inputs.department} placeholder="Any department" onChange={event => set('department')(event.target.value)} />}
        </Field>
        <Field label="Location">
          {control => <input {...control} value={inputs.location} placeholder="Any location" onChange={event => set('location')(event.target.value)} />}
        </Field>
        <Field label="Job type">
          {control => (
            <select {...control} value={inputs.jobType} onChange={event => set('jobType')(event.target.value)}>
              <option value="">All job types</option>
              {JOB_TYPE_OPTIONS.map(option => (
                <option key={option.value} value={option.value}>
                  {option.label}
                </option>
              ))}
            </select>
          )}
        </Field>
        {hasFilters && (
          <button
            type="button"
            className="text-button clear-filters"
            onClick={() => setInputs({ search: '', department: '', location: '', jobType: '' })}
          >
            Clear filters
          </button>
        )}
      </form>

      <section className="results" aria-labelledby="results-title" aria-busy={jobs.isFetching}>
        <div className="results-bar">
          <h2 id="results-title" className="visually-hidden">
            Results
          </h2>
          <p className="muted" role="status">
            {jobs.data ? `${jobs.data.totalElements} ${jobs.data.totalElements === 1 ? 'job' : 'jobs'} found` : ''}
          </p>
          {(typing || updating) && (
            <span className="updating" aria-hidden="true">
              <Spinner small /> Updating results…
            </span>
          )}
        </div>

        {jobs.isPending ? (
          <LoadingBlock label="Loading jobs…" />
        ) : jobs.isError && !jobs.data ? (
          <ErrorBlock error={jobs.error} onRetry={() => void jobs.refetch()} />
        ) : jobs.data && jobs.data.content.length === 0 ? (
          <EmptyState title="No open roles match these filters">Try a different keyword or clear the filters.</EmptyState>
        ) : (
          <div className={`job-list${updating ? ' is-stale' : ''}`}>
            {jobs.isError && <ErrorBlock error={jobs.error} onRetry={() => void jobs.refetch()} title="Showing earlier results" />}
            {jobs.data?.content.map(job => <JobCard key={job.id} job={job} />)}
          </div>
        )}

        {jobs.data && (
          <Pagination
            label="Job results pages"
            page={page}
            totalPages={jobs.data.totalPages}
            totalElements={jobs.data.totalElements}
            disabled={jobs.isFetching}
            onPageChange={next => {
              setParams(writeParams(applied, next))
              window.scrollTo({ top: 0 })
            }}
          />
        )}
      </section>
    </>
  )
}

function JobCard({ job }: { job: JobPublic }) {
  const location = useLocation()
  const mustHaves = job.requirements.filter(requirement => requirement.mandatory).length
  return (
    <article className="job-card">
      <div className="job-top">
        <div>
          <div className="tag-row">
            {job.department && <span className="tag">{job.department}</span>}
            {job.organizationName && <span className="org-name">{job.organizationName}</span>}
          </div>
          <h3>
            <Link to={`/jobs/${job.id}`} state={{ fromSearch: location.search }} className="card-link">
              {job.title}
            </Link>
          </h3>
          <p className="muted">
            {job.location} <span className="dot" aria-hidden="true">·</span> {jobTypeLabel(job.jobType)}{' '}
            <span className="dot" aria-hidden="true">·</span> {experienceLabel(job.experienceLevel)}
          </p>
        </div>
        <span className="job-symbol" aria-hidden="true">
          ↗
        </span>
      </div>
      <p className="job-description clamp">{job.description}</p>
      <div className="job-bottom">
        <span className="salary">{formatSalaryRange(job.minimumSalary, job.maximumSalary)}</span>
        {mustHaves > 0 && <span className="muted">{mustHaves} must-have {mustHaves === 1 ? 'requirement' : 'requirements'}</span>}
        <span className="muted">{job.deadline ? `Apply by ${formatDate(job.deadline)}` : 'Applications open'}</span>
      </div>
    </article>
  )
}
