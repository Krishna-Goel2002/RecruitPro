import { useEffect, useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Link, useLocation, useSearchParams } from 'react-router-dom'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { jobsApi } from '../api/endpoints'
import { queryKeys } from '../api/queryKeys'
import type { JobPublic, JobSearchParams, JobType } from '../api/types'
import { ErrorState } from '../components/Feedback'
import {
  Badge, Button, Drawer, EmptyState, Field, Input, LoadingRegion, PageHeader, Pagination, Select, Skeleton, Spinner, useMediaQuery,
} from '../design/components'
import { BriefcaseBusiness, Check, Icon, Search, SlidersHorizontal, X } from '../design/icons'
import { formatDate, plural } from '../design/lib/format'
import { stagger } from '../design/motion'
import { closesIn, formatSalaryRange } from '../lib/format'
import { useDebouncedValue } from '../lib/hooks'
import { JOB_TYPE_OPTIONS, experienceLabel, jobTypeLabel } from '../lib/labels'
import { recentApplicationsQuery } from '../lib/queries'
import './common.css'
import './jobs/jobs.css'

const PAGE_SIZE = 10
const DEBOUNCE_MS = 300

interface Filters {
  search: string
  department: string
  location: string
  jobType: JobType | ''
}
const NO_FILTERS: Filters = { search: '', department: '', location: '', jobType: '' }

function readFilters(params: URLSearchParams): Filters {
  const jobType = params.get('jobType') ?? ''
  return {
    search: params.get('search') ?? '',
    department: params.get('department') ?? '',
    location: params.get('location') ?? '',
    jobType: JOB_TYPE_OPTIONS.some(option => option.value === jobType) ? (jobType as JobType) : '',
  }
}

const sameFilters = (a: Filters, b: Filters) => a.search === b.search && a.department === b.department && a.location === b.location && a.jobType === b.jobType

function writeParams(filters: Filters, page: number): URLSearchParams {
  const params = new URLSearchParams()
  if (filters.search.trim()) params.set('search', filters.search.trim())
  if (filters.department.trim()) params.set('department', filters.department.trim())
  if (filters.location.trim()) params.set('location', filters.location.trim())
  if (filters.jobType) params.set('jobType', filters.jobType)
  if (page > 0) params.set('page', String(page))
  return params
}

/** Filters as they look after a round trip through the URL (trimmed, unknown job types dropped). */
export function normalize(filters: Filters): Filters {
  return readFilters(writeParams(filters, 0))
}

/**
 * Find jobs (REDESIGN_PLAN A2): search + inline filters on desktop, search + a Filters sheet on phones; hairline
 * rows that are one link each, with an "Applied" marker and "Closes in N days". Filters live in the URL.
 */
export function JobsPage() {
  const [params, setParams] = useSearchParams()
  const applied = useMemo(() => readFilters(params), [params])
  const page = Math.max(0, Number.parseInt(params.get('page') ?? '0', 10) || 0)
  const phone = !useMediaQuery('(min-width: 900px)')
  const [sheetOpen, setSheetOpen] = useState(false)

  // The inputs stay mounted; typing updates local state, and the URL (and query) follow after 300 ms.
  const [inputs, setInputs] = useState<Filters>(applied)
  const debounced = useDebouncedValue(inputs, DEBOUNCE_MS)
  /** The filters this page last wrote to the URL (normalised the way the URL stores them). */
  const lastWritten = useRef<Filters>(applied)
  useEffect(() => {
    if (!sameFilters(normalize(debounced), readFilters(params))) {
      const next = writeParams(debounced, 0)
      lastWritten.current = readFilters(next)
      setParams(next, { replace: true })
    }
    // Only the debounced input triggers a URL update; `params` is read at that moment on purpose.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced])
  // The URL changed from outside (Back/Forward, the "Find jobs" nav link): the inputs follow it, otherwise they kept
  // the old words, "Updating…" spun for ever and the results did not match the search box.
  useEffect(() => {
    if (sameFilters(applied, lastWritten.current)) return
    lastWritten.current = applied
    setInputs(applied)
  }, [applied])

  const searchParams: JobSearchParams = { page, size: PAGE_SIZE, ...applied }
  const jobs = useQuery({ queryKey: queryKeys.jobs(searchParams), queryFn: () => jobsApi.search(searchParams), placeholderData: keepPreviousData })
  const applications = useQuery(recentApplicationsQuery)
  const appliedJobs = useMemo(() => new Map((applications.data?.content ?? []).filter(a => a.status !== 'WITHDRAWN').map(a => [a.jobId, a.id])), [applications.data])
  const appliedOnPage = (jobs.data?.content ?? []).filter(j => appliedJobs.has(j.id)).length

  // Compared as the URL stores them: a trailing space must not leave "Updating…" on screen.
  const typing = !sameFilters(normalize(inputs), applied)
  const updating = jobs.isFetching && jobs.isPlaceholderData
  const extraCount = [inputs.department, inputs.location, inputs.jobType].filter(Boolean).length
  const hasFilters = Boolean(inputs.search) || extraCount > 0
  const set = (key: keyof Filters) => (value: string) => setInputs(current => ({ ...current, [key]: value }))
  const clear = () => setInputs(NO_FILTERS)

  const extraFields = (
    <>
      <Field label="Department">
        <Input value={inputs.department} maxLength={100} placeholder="Any department" onChange={e => set('department')(e.target.value)} />
      </Field>
      <Field label="Location">
        <Input value={inputs.location} maxLength={255} placeholder="Any location" onChange={e => set('location')(e.target.value)} />
      </Field>
      <Field label="Job type">
        <Select value={inputs.jobType} onChange={e => set('jobType')(e.target.value)}>
          <option value="">All job types</option>
          {JOB_TYPE_OPTIONS.map(option => (
            <option key={option.value} value={option.value}>
              {option.label}
            </option>
          ))}
        </Select>
      </Field>
    </>
  )

  return (
    <>
      <PageHeader title="Find jobs" description="Open roles you can apply to now. A person reviews every application." />

      <form className="j-filters" role="search" aria-label="Filter jobs" onSubmit={e => e.preventDefault()}>
        {/* GET /jobs `search` matches the job title only (JobService.listForCandidate), so the label says so. */}
        <Field label="Job title" hideLabel={phone} className="j-search">
          <Input
            type="search"
            leading={<Icon icon={Search} size={16} />}
            value={inputs.search}
            maxLength={255}
            placeholder="Search job titles"
            onChange={e => set('search')(e.target.value)}
          />
        </Field>
        {phone ? (
          <Button icon={SlidersHorizontal} onClick={() => setSheetOpen(true)} aria-label={extraCount ? `Filters, ${extraCount} applied` : 'Filters'}>
            Filters
            {extraCount > 0 && (
              <span className="j-filter-count numeric" aria-hidden="true">
                {extraCount}
              </span>
            )}
          </Button>
        ) : (
          extraFields
        )}
      </form>

      <div className="c-toolbar j-results-bar">
        <p className="c-status-line" role="status">
          {jobs.data ? `${plural(jobs.data.totalElements, 'open role')}` : ''}
          {appliedOnPage > 0 ? ` · you have applied to ${appliedOnPage} of these` : ''}
          {(typing || updating) && (
            <span className="c-status-line" aria-hidden="true">
              <Spinner size={12} /> Updating…
            </span>
          )}
        </p>
        {hasFilters && (
          <Button variant="ghost" size="sm" icon={X} onClick={clear}>
            Clear filters
          </Button>
        )}
      </div>

      <section aria-label="Results" aria-busy={jobs.isFetching}>
        {jobs.isPending ? (
          <JobsSkeleton />
        ) : jobs.isError && !jobs.data ? (
          <ErrorState error={jobs.error} onRetry={() => void jobs.refetch()} title="Jobs could not be loaded" />
        ) : jobs.data && jobs.data.content.length === 0 ? (
          page > 0 && jobs.data.totalElements > 0 ? (
            // An old or hand-edited ?page= past the last page: the roles exist, just not on this page.
            <EmptyState
              icon={BriefcaseBusiness}
              title="This page of results is empty"
              description={`There are ${plural(jobs.data.totalElements, 'open role')} on earlier pages.`}
              action={<Button onClick={() => setParams(writeParams(applied, 0))}>Go to the first page</Button>}
            />
          ) : hasFilters ? (
            <EmptyState
              icon={Search}
              title={inputs.search ? `No open roles match “${inputs.search}”` : 'No open roles match these filters'}
              description="Try part of a job title or department name, or clear the filters to see every open role."
              action={<Button onClick={clear}>Clear filters</Button>}
            />
          ) : (
            <EmptyState icon={BriefcaseBusiness} title="No open roles right now" description="New roles appear here as soon as employers publish them." />
          )
        ) : (
          <>
            {jobs.isError && <ErrorState error={jobs.error} onRetry={() => void jobs.refetch()} title="Could not refresh the results" stale />}
            <JobList jobs={jobs.data?.content ?? []} applied={appliedJobs} dim={updating} />
          </>
        )}
        {jobs.data && jobs.data.totalElements > PAGE_SIZE && (
          <Pagination
            className="j-pagination"
            page={page + 1}
            pageSize={PAGE_SIZE}
            total={jobs.data.totalElements}
            onPageChange={next => {
              setParams(writeParams(applied, next - 1))
              window.scrollTo({ top: 0 })
            }}
          />
        )}
      </section>

      <Drawer
        open={sheetOpen}
        onClose={() => setSheetOpen(false)}
        title="Filters"
        footer={
          <>
            <Button variant="ghost" onClick={() => setInputs(current => ({ ...current, department: '', location: '', jobType: '' }))}>
              Reset
            </Button>
            <Button variant="primary" icon={Check} onClick={() => setSheetOpen(false)}>
              {jobs.data ? `Show ${plural(jobs.data.totalElements, 'role')}` : 'Show roles'}
            </Button>
          </>
        }
      >
        <div className="j-sheet-fields">{extraFields}</div>
      </Drawer>
    </>
  )
}

function JobsSkeleton() {
  return (
    <LoadingRegion label="Loading jobs">
      <ul className="c-rows" aria-hidden="true">
        {[0, 1, 2, 3].map(i => (
          <li key={i} className="c-skel-row">
            <Skeleton width={i % 2 ? 220 : 280} height={14} />
            <Skeleton width={320} height={10} />
            <Skeleton width="70%" height={10} />
          </li>
        ))}
      </ul>
    </LoadingRegion>
  )
}

function JobList({ jobs, applied, dim }: { jobs: JobPublic[]; applied: Map<number, number>; dim: boolean }) {
  const location = useLocation()
  const ref = useRef<HTMLUListElement>(null)
  const played = useRef(false)
  useLayoutEffect(() => {
    if (played.current || !ref.current || jobs.length === 0) return
    played.current = true
    stagger(ref.current.children as unknown as Iterable<HTMLElement>)
  }, [jobs])

  return (
    <ul ref={ref} className={`c-rows j-list${dim ? ' c-dim' : ''}`} data-closed="">
      {jobs.map(job => {
        const mustHaves = job.requirements.filter(r => r.mandatory).length
        const applicationId = applied.get(job.id)
        const days = job.deadline ? (new Date(job.deadline).getTime() - Date.now()) / 86_400_000 : null
        return (
          <li key={job.id} className="c-linkrow j-row">
            <div className="j-row-main">
              <h2 className="j-title">
                <Link to={`/jobs/${job.id}`} state={{ fromSearch: location.search }} className="c-linkrow-link">
                  {job.title}
                </Link>
                {applicationId && (
                  <Badge tone="success" dot>
                    Applied
                  </Badge>
                )}
              </h2>
              <p className="c-meta-line">
                {job.organizationName && <span className="j-org">{job.organizationName}</span>}
                <span>{job.location}</span>
                <span>{jobTypeLabel(job.jobType)}</span>
                <span>{experienceLabel(job.experienceLevel)}</span>
              </p>
              <p className="j-desc">{job.description}</p>
            </div>
            <div className="j-row-facts">
              <span className="j-salary numeric">{formatSalaryRange(job.minimumSalary, job.maximumSalary)}</span>
              <span className="c-meta">{mustHaves > 0 ? plural(mustHaves, 'must-have') : 'No must-haves listed'}</span>
              {job.deadline ? (
                <time
                  className="c-meta j-deadline"
                  dateTime={job.deadline}
                  title={`Apply by ${formatDate(job.deadline, { dateStyle: 'medium' })}`}
                  data-soon={days !== null && days < 5 ? true : undefined}
                >
                  {closesIn(job.deadline)}
                  <span className="sr-only">, apply by {formatDate(job.deadline, { dateStyle: 'medium' })}</span>
                </time>
              ) : (
                <span className="c-meta">No closing date</span>
              )}
            </div>
          </li>
        )
      })}
    </ul>
  )
}
