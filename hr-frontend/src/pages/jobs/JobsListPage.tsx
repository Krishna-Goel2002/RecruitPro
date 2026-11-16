import { useEffect, useMemo, useRef, useState } from 'react'
import { keepPreviousData, useQueries, useQuery } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { jobsApi } from '../../api/jobs'
import { qk } from '../../api/queryKeys'
import type { JobListParams, JobStaff, JobStatus } from '../../api/types'
import { useOptionalAssistant } from '../../assistant/AssistantProvider'
import { useAuth } from '../../auth/AuthContext'
import { QueryError } from '../../components/QueryError'
import { useShortcuts } from '../../components/Shortcuts'
import { JobStatusBadge } from '../../components/StatusBadges'
import {
  Button, DataTable, EmptyState, IconButton, Input, Menu, PageHeader, Pagination, SummaryStrip, TabPanel, Tabs, buttonProps, type Column, type SummaryItem,
} from '../../design/components'
import { formatDate, formatNumber, plural, relativeTime } from '../../design/lib/format'
import { BriefcaseBusiness, Ellipsis, Icon, MessagesSquare, Plus, Search, X } from '../../design/icons'
import { humanize } from '../../lib/format'
import { useDebouncedValue } from '../../lib/hooks'
import { newSince } from '../../lib/lastSeen'
import { oneOf, useClampPage, useSearchState } from '../../lib/useSearchState'
import { useJobActions } from './jobActions'
import './jobs.css'

type StatusTab = JobStatus | 'ALL'
const TABS: { value: StatusTab; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'PUBLISHED', label: 'Published' },
  { value: 'DRAFT', label: 'Drafts' },
  { value: 'ON_HOLD', label: 'On hold' },
  { value: 'CLOSED', label: 'Closed' },
  { value: 'CANCELLED', label: 'Cancelled' },
]
const PAGE_SIZE = 20

/** "in 12 days" / "Closed" / "—", with the absolute date for the tooltip. */
function closes(job: JobStaff): { text: string; title?: string; soon?: boolean } {
  if (job.status === 'CLOSED' || job.status === 'CANCELLED') return { text: job.status === 'CLOSED' ? 'Closed' : 'Cancelled' }
  if (!job.deadline) return { text: 'No deadline' }
  const ms = new Date(job.deadline).getTime() - Date.now()
  return { text: ms <= 0 ? 'Deadline passed' : relativeTime(job.deadline), title: formatDate(job.deadline), soon: ms > 0 && ms < 3 * 86_400_000 }
}

/**
 * Jobs (the home screen for managers and recruiters): status tabs with counts that ARE the filter, a debounced title
 * search, and a table whose rows open the job. Managers get a row menu for status changes (hold/resume with Undo;
 * publish/close confirmed). Everything is in the URL: ?status, ?q, ?page.
 */
export function JobsListPage() {
  const { can } = useAuth()
  const canManage = can('jobs.manage')
  const canExport = can('audit.jobExport')
  const navigate = useNavigate()
  const assistant = useOptionalAssistant()
  const state = useSearchState()
  const status = oneOf<StatusTab>(state.get('status'), TABS.map(t => t.value), 'ALL')
  const search = state.get('q')
  const page = state.getPage()
  const actions = useJobActions()

  const [searchInput, setSearchInput] = useState(search)
  const debounced = useDebouncedValue(searchInput.trim(), 300)
  useEffect(() => { setSearchInput(search) }, [search])
  useEffect(() => {
    if (debounced !== search) state.set({ q: debounced }, { replace: true })
    // only react to the debounced input
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [debounced])
  const searchRef = useRef<HTMLInputElement>(null)
  useShortcuts({ '/': event => { event.preventDefault(); searchRef.current?.focus() } })

  const params: JobListParams = { page, size: PAGE_SIZE, status: status === 'ALL' ? undefined : status, search: search || undefined, sort: 'createdAt', direction: 'DESC' }
  const list = useQuery({ queryKey: qk.jobList(params), queryFn: ({ signal }) => jobsApi.list(params, signal), placeholderData: keepPreviousData })
  useClampPage(state, page, list.isPlaceholderData ? undefined : list.data)

  // API gap: no GET /jobs/stats. Six size=1 calls in parallel, cached for a minute.
  const stats = useQueries({
    queries: TABS.map(tab => ({
      queryKey: qk.jobStats(tab.value),
      queryFn: ({ signal }: { signal: AbortSignal }) => jobsApi.list({ page: 0, size: 1, status: tab.value === 'ALL' ? undefined : tab.value }, signal),
      staleTime: 60_000,
    })),
  })
  const counts = Object.fromEntries(TABS.map((tab, i) => [tab.value, stats[i].data?.totalElements])) as Record<StatusTab, number | undefined>
  const tabs = TABS.filter(tab => tab.value !== 'CANCELLED' || (counts.CANCELLED ?? 0) > 0 || status === 'CANCELLED')
    .map(tab => ({ value: tab.value, label: tab.label, count: counts[tab.value] }))

  const filtered = Boolean(search) || status !== 'ALL'
  const data = list.data
  // Header summary from what is on screen: new applicants since your last visit, deadlines in the next 7 days.
  const summary = useMemo(() => {
    const rows = data?.content ?? []
    let fresh = 0
    let closing = 0
    for (const job of rows) {
      const n = job.applicationCount != null ? newSince(job.id, job.applicationCount) : null
      if (n && n > 0) fresh += n
      if (job.status === 'PUBLISHED' && job.deadline) {
        const ms = new Date(job.deadline).getTime() - Date.now()
        if (ms > 0 && ms < 7 * 86_400_000) closing++
      }
    }
    return { fresh, closing }
  }, [data])
  const noJobsAtAll = counts.ALL === 0 && !search

  // At a glance + needs attention: each tile filters the list to the jobs it counts (press again to show all).
  const toggle = (value: StatusTab) => state.set({ status: status === value ? null : value === 'ALL' ? null : value })
  const pulse: SummaryItem[] = counts.ALL != null && counts.ALL > 0 ? [
    { key: 'open', label: 'Open jobs', value: counts.PUBLISHED, hint: 'Accepting applications', onSelect: () => toggle('PUBLISHED'), pressed: status === 'PUBLISHED' },
    { key: 'new', label: 'New applicants', value: summary.fresh, hint: 'Since your last visit', tone: summary.fresh > 0 ? 'attention' : undefined },
    { key: 'closing', label: 'Closing soon', value: summary.closing, hint: 'Deadline within 7 days', tone: summary.closing > 0 ? 'attention' : undefined },
    ...(canManage ? [{ key: 'drafts', label: 'Drafts', value: counts.DRAFT, hint: 'Not visible to candidates', tone: (counts.DRAFT ?? 0) > 0 ? 'attention' as const : undefined, onSelect: () => toggle('DRAFT'), pressed: status === 'DRAFT' }] : []),
    { key: 'hold', label: 'On hold', value: counts.ON_HOLD, hint: 'Paused, not taking applications', onSelect: () => toggle('ON_HOLD'), pressed: status === 'ON_HOLD' },
  ] : []

  const columns = useMemo<Column<JobStaff>[]>(() => [
    {
      key: 'title',
      header: 'Job',
      primary: true,
      cell: job => (
        <span className="hra-job-cell">
          <Link className="hra-row-link" to={`/jobs/${job.id}`}>{job.title}</Link>
          <span className="hra-job-sub">{humanize(job.jobType)} · {humanize(job.experienceLevel)}</span>
        </span>
      ),
    },
    { key: 'department', header: 'Department', cell: job => job.department, hideWhenStacked: true },
    { key: 'location', header: 'Location', cell: job => <span className="hra-clamp" title={job.location}>{job.location}</span>, hideWhenStacked: true },
    { key: 'status', header: 'Status', width: '120px', cell: job => <JobStatusBadge status={job.status} /> },
    {
      key: 'applicants',
      header: 'Applicants',
      width: '128px',
      cell: job => {
        if (job.applicationCount == null) return <span className="subtle">—</span>
        const fresh = newSince(job.id, job.applicationCount)
        return (
          <span className="hra-applicants numeric">
            <span>{formatNumber(job.applicationCount)}<span className="hra-stack-label"> applicants</span></span>
            {fresh != null && fresh > 0 && <span className="hra-new">{formatNumber(fresh)} new</span>}
          </span>
        )
      },
    },
    {
      key: 'closes',
      header: 'Closes',
      width: '132px',
      hideWhenStacked: true,
      cell: job => {
        const c = closes(job)
        const text = <span className={c.soon ? 'hra-soon' : undefined}>{c.text}</span>
        return c.title ? <time dateTime={job.deadline ?? undefined} title={c.title}>{text}</time> : text
      },
    },
    {
      key: 'created',
      header: 'Created',
      width: '112px',
      hideWhenStacked: true,
      cell: job => <time dateTime={job.createdAt} title={formatDate(job.createdAt)} className="subtle">{relativeTime(job.createdAt)}</time>,
    },
    ...(canManage || canExport
      ? [{
          key: 'menu',
          header: <span className="sr-only">Actions</span>,
          width: '48px',
          align: 'end' as const,
          cell: (job: JobStaff) => (
            <Menu
              label={`Actions for ${job.title}`}
              items={actions.menuItems(job, { canManage, canExport, includeEdit: true })}
              trigger={props => <IconButton {...props} label={`Actions for ${job.title}`} icon={Ellipsis} size="sm" noTooltip />}
            />
          ),
        }]
      : []),
  ], [canManage, canExport, actions])

  return (
    <div className="hra-jobs" data-width="wide">
      <PageHeader
        title="Jobs"
        meta={counts.ALL != null && counts.ALL > 0 ? [
          <span key="pub">{plural(counts.PUBLISHED ?? 0, 'published job')}</span>,
        ] : undefined}
        actions={<>
          {assistant && counts.ALL != null && counts.ALL > 0 && (
            <Button icon={MessagesSquare} onClick={() => assistant.ask('How are my published jobs doing?')}>Ask about jobs</Button>
          )}
          {canManage && <Link {...buttonProps('primary')} to="/jobs/new"><Icon icon={Plus} size={16} /><span className="ui-btn-label">New job</span></Link>}
        </>}
      />

      {pulse.length > 0 && <SummaryStrip label="Jobs at a glance" items={pulse} className="hra-pulse" />}

      <Tabs
        label="Job status"
        idBase="jobs-status"
        items={tabs}
        value={status}
        onChange={value => state.set({ status: value === 'ALL' ? null : value })}
        className="hra-status-tabs"
      />

      <TabPanel idBase="jobs-status" value={status} active={status} className="hra-jobs-panel">
      {noJobsAtAll && status === 'ALL' ? (
        canManage ? (
          <EmptyState
            icon={BriefcaseBusiness}
            title="Create your first job"
            description="Describe the role, and we suggest requirements from your description. Applicants are ranked by the evidence they show for each one."
            action={
              <span className="hra-empty-actions">
                <Link {...buttonProps('primary')} to="/jobs/new"><Icon icon={Plus} size={16} /><span className="ui-btn-label">New job</span></Link>
                {assistant && <Button icon={MessagesSquare} onClick={() => assistant.ask('Post a new job')}>Draft it with the assistant</Button>}
              </span>
            }
            className="hra-first-job"
          />
        ) : (
          <EmptyState icon={BriefcaseBusiness} title="No jobs yet" description="Jobs your HR managers create appear here. You can review their applicants once they are published." className="hra-first-job" />
        )
      ) : (<>
      <div className="hra-list-toolbar" role="search">
        <Input
          ref={searchRef}
          type="search"
          aria-label="Search jobs by title"
          placeholder="Search job titles"
          value={searchInput}
          onChange={event => setSearchInput(event.target.value)}
          leading={<Icon icon={Search} size={16} />}
          trailing={searchInput ? <IconButton label="Clear search" icon={X} size="sm" onClick={() => setSearchInput('')} noTooltip /> : <kbd className="hra-kbd" aria-hidden="true">/</kbd>}
          className="hra-search"
        />
        <p className="hra-count numeric" aria-live="polite">
          {data ? plural(data.totalElements, 'job') : ''}
        </p>
      </div>

      {list.isError && !data ? (
        <QueryError error={list.error} title="Jobs could not be loaded" onRetry={() => void list.refetch()} retrying={list.isFetching} />
      ) : (
        <div className="hra-fade" data-stale={list.isPlaceholderData || undefined}>
          <DataTable<JobStaff>
            caption={`Jobs${status !== 'ALL' ? `, ${tabs.find(t => t.value === status)?.label.toLowerCase()}` : ''}${search ? `, matching “${search}”` : ''}`}
            columns={columns}
            rows={data?.content ?? []}
            rowKey={job => job.id}
            loading={list.isPending}
            loadingRows={8}
            onRowActivate={job => navigate(`/jobs/${job.id}`)}
            empty={
              filtered && !noJobsAtAll ? (
                <EmptyState
                  compact
                  icon={Search}
                  title={search ? `No jobs match “${search}”` : `No ${tabs.find(t => t.value === status)?.label.toLowerCase() ?? ''} jobs`}
                  description={search ? 'Search looks at job titles only.' : 'Jobs move here when their status changes.'}
                  action={<Button size="sm" onClick={() => { setSearchInput(''); state.set({ q: null, status: null }) }}>Clear filters</Button>}
                />
              ) : canManage ? (
                <EmptyState
                  icon={BriefcaseBusiness}
                  title="Create your first job"
                  description="Describe the role, and we suggest requirements from your description. Applicants are ranked by the evidence they show for each one."
                  action={<Link {...buttonProps('primary')} to="/jobs/new"><Icon icon={Plus} size={16} /><span className="ui-btn-label">New job</span></Link>}
                />
              ) : (
                <EmptyState icon={BriefcaseBusiness} title="No jobs yet" description="Jobs your HR managers create appear here. You can review their applicants once they are published." />
              )
            }
          />
        </div>
      )}

      {data && data.totalElements > PAGE_SIZE && (
        <Pagination page={data.page + 1} pageSize={PAGE_SIZE} total={data.totalElements} onPageChange={next => state.set({ page: next - 1 })} />
      )}
      </>)}
      </TabPanel>
      {actions.dialog}
    </div>
  )
}
