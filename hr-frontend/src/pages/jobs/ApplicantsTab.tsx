import { useCallback, useEffect, useMemo, useRef, useState, type FocusEvent, type KeyboardEvent } from 'react'
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { errorMessage } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { ApplicantListParams, ApplicantSummary, ApplicationStatus, JobStaff, RankGroup } from '../../api/types'
import { useOptionalAssistant } from '../../assistant/AssistantProvider'
import { useAuth } from '../../auth/AuthContext'
import { QueryError } from '../../components/QueryError'
import { Kbd, ShortcutSheet, useShortcuts } from '../../components/Shortcuts'
import { ApplicationStatusBadge } from '../../components/StatusBadges'
import {
  Alert, Button, DataTable, DensityToggle, EmptyState, IconButton, Menu, Pagination, SegmentedControl, Spinner, Tag, Tooltip,
  buttonProps, useTableDensity, useToast, type Column, type RowGroup,
} from '../../design/components'
import { formatDate, formatNumber, plural, relativeTime } from '../../design/lib/format'
import {
  ArrowRight, ChevronDown, Ellipsis, Eye, EyeOff, Icon, Info, LayoutGrid, Link2, MessagesSquare, RefreshCw, Search, TriangleAlert, UserRoundCheck, Users, X,
} from '../../design/icons'
import { flagInfo } from '../../lib/format'
import { usePendingPoll, usePollRef } from '../../lib/hooks'
import { lastSeen, markSeen, readFlag, writeFlag } from '../../lib/lastSeen'
import { useSearchState } from '../../lib/useSearchState'
import { ApplicantCompare } from './ApplicantCompare'
import { ApplicantPeek } from './ApplicantPeek'
import { applyOverride, useListDecisions } from './listDecisions'
import {
  COVERAGE_NOTE, CoverageCell, EvidenceStrip, GROUP_COPY, MATCH_NOTE, RequirementLegend, canListDecide, stripRequirements, useApplicantDetails,
} from './ranking'

const PAGE_SIZE = 25
const HINT_FLAG = 'hr-hint-coverage-dismissed'

type View = 'ALL' | 'SUBMITTED' | 'SHORTLISTED' | 'INTERVIEWING'
const VIEWS: { value: View; label: string }[] = [
  { value: 'ALL', label: 'All' },
  { value: 'SUBMITTED', label: 'To review' },
  { value: 'SHORTLISTED', label: 'Shortlisted' },
  { value: 'INTERVIEWING', label: 'Interviewing' },
]
const MORE_VIEWS: { value: ApplicationStatus; label: string }[] = [
  { value: 'SELECTED', label: 'Selected' },
  { value: 'REJECTED', label: 'Not progressed' },
  { value: 'WITHDRAWN', label: 'Withdrawn' },
]

/**
 * The applicant ranking (B4): a compact table grouped by the server's ranking groups, an evidence strip per
 * applicant, coverage numbers that are explained as shares (never probabilities), saved views in the URL, keyboard
 * triage (j/k, Enter, Space peek, x select, s shortlist, r not progressed, c compare, ?), a quick-peek drawer, a
 * side-by-side compare, and decisions sent after a 5-second Undo window. Rows glide when the order changes (M7).
 */
export function ApplicantsTab({ job }: { job: JobStaff }) {
  const { can } = useAuth()
  const assistant = useOptionalAssistant()
  const canDecide = can('applicants.decide')
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const navigate = useNavigate()
  const location = useLocation()
  const state = useSearchState()
  const status = state.get('status') as ApplicationStatus | ''
  const sort = state.get('sort') === 'appliedAt' ? 'appliedAt' : 'rank'
  const page = state.getPage()
  const peekId = Number(state.get('peek')) || null
  const compareIds = state.get('compare').split(',').map(Number).filter(n => Number.isInteger(n) && n > 0).slice(0, 3)
  const params: ApplicantListParams = { page, size: PAGE_SIZE, status: status || undefined, sort }
  const [density, setDensity] = useTableDensity('compact')
  const [helpOpen, setHelpOpen] = useState(false)
  const [hintHidden, setHintHidden] = useState(() => readFlag(HINT_FLAG))

  // "New since your last visit": read the previous visit before recording this one.
  const [previousVisit] = useState(() => lastSeen(job.id)?.at ?? null)
  useEffect(() => { markSeen(job.id, job.applicationCount) }, [job.id, job.applicationCount])

  const listKey = JSON.stringify([job.id, params])
  const pollRef = usePollRef()
  const list = useQuery({
    queryKey: qk.applicantList(job.id, params),
    queryFn: ({ signal }) => reviewApi.list(job.id, params, signal),
    refetchInterval: pollRef.interval,
    placeholderData: keepPreviousData,
  })
  const rows = useMemo(() => list.data?.content ?? [], [list.data])
  const pendingCount = rows.filter(row => row.matchStatus === 'PENDING').length
  const poll = usePendingPoll(pendingCount > 0, listKey)
  pollRef.bind(poll.refetchInterval)

  const details = useApplicantDetails(job.id, rows)
  const requirements = useMemo(() => stripRequirements(job.requirements), [job.requirements])
  const decisions = useListDecisions(job)

  const rematch = useMutation({
    mutationFn: () => reviewApi.rematch(job.id),
    onSuccess: result => {
      toast({
        tone: 'success',
        title: result.enqueued === 0 ? 'Nothing to re-match' : `Re-matching ${plural(result.enqueued, 'application')}`,
        body: result.enqueued === 0 ? 'No application is waiting for analysis.' : 'The order updates here as results arrive.',
      })
      poll.restart()
      void queryClient.invalidateQueries({ queryKey: qk.applicants(job.id) })
    },
    onError: cause => toast({ tone: 'danger', title: 'Re-matching could not start', body: errorMessage(cause) }),
  })

  // Rows apply optimistic decisions (status flips at once; a thin progress line while the send window runs).
  const view = rows.map(row => applyOverride(row, decisions.overrides.get(row.applicationId)))
  const order = view.map(row => row.applicationId)

  // M7: a row that changed ranking group since the last data gets the accent wash.
  const groupsBefore = useRef(new Map<number, RankGroup>())
  const tableRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const before = groupsBefore.current
    const moved = rows.filter(row => before.has(row.applicationId) && before.get(row.applicationId) !== row.rankGroup).map(row => row.applicationId)
    groupsBefore.current = new Map(rows.map(row => [row.applicationId, row.rankGroup]))
    if (!moved.length) return
    const els = moved
      .map(id => tableRef.current?.querySelector<HTMLElement>(`tr[data-row="${id}"]`))
      .filter((el): el is HTMLElement => !!el)
    els.forEach(el => el.classList.add('is-moved'))
    const timer = window.setTimeout(() => els.forEach(el => el.classList.remove('is-moved')), 1300)
    return () => window.clearTimeout(timer)
  }, [rows])

  // Selection (compare, bulk decisions) and the keyboard-focused row.
  const [selected, setSelected] = useState<Set<number>>(new Set())
  useEffect(() => { setSelected(new Set()) }, [listKey])
  const [focusedId, setFocusedId] = useState<number | null>(null)

  const backTo = `${location.pathname}${location.search}`
  const openApplicant = useCallback((id: number) => {
    navigate(`/jobs/${job.id}/applicants/${id}`, { state: { back: backTo, order } })
  }, [navigate, job.id, backTo, order])
  const setPeek = (id: number | null) => state.set({ peek: id, page: page || null }, { replace: id != null && peekId != null })
  const setCompare = (ids: number[] | null) => state.set({ compare: ids && ids.length ? ids.join(',') : null, page: page || null })

  const focusRow = (id: number) => {
    const el = tableRef.current?.querySelector<HTMLElement>(`tr[data-row="${id}"]`)
    el?.focus()
    el?.scrollIntoView({ block: 'nearest' })
  }
  const moveFocus = (delta: number) => {
    if (!order.length) return
    const index = focusedId != null ? order.indexOf(focusedId) : -1
    const next = order[Math.max(0, Math.min(order.length - 1, index < 0 ? 0 : index + delta))]
    if (peekId != null) setPeek(next)
    focusRow(next)
    setFocusedId(next)
  }
  const current = () => (peekId ?? focusedId) as number | null
  const decide = (action: 'SHORTLIST' | 'REJECT', ids: number[]) => {
    if (!canDecide || !ids.length) return
    decisions.request(action, view.filter(row => ids.includes(row.applicationId)), details)
  }

  useShortcuts(
    {
      j: () => moveFocus(1),
      k: () => moveFocus(-1),
      ' ': event => {
        const id = current()
        if (id == null) return
        event.preventDefault()
        setPeek(peekId === id ? null : id)
      },
      x: () => {
        const id = focusedId
        if (id == null) return
        setSelected(currentSel => {
          const next = new Set(currentSel)
          if (next.has(id)) next.delete(id)
          else if (next.size < 25) next.add(id)
          return next
        })
      },
      s: () => { const id = current(); if (id != null) decide('SHORTLIST', [id]) },
      r: () => { const id = current(); if (id != null) decide('REJECT', [id]) },
      c: () => { if (selected.size >= 2) setCompare([...selected].slice(0, 3)) },
      '?': () => setHelpOpen(true),
    },
    true,
    // Inside the peek drawer j/k/s/r/space keep working (only while the drawer is the one open dialog).
    peekId != null && !decisions.isOpen && !helpOpen ? ['j', 'k', ' ', 's', 'r'] : [],
  )

  // DataTable handles ↑/↓/Enter; this tracks which row has focus so j/k continue from it.
  const onFocusCapture = (event: FocusEvent<HTMLDivElement>) => {
    const tr = (event.target as HTMLElement).closest<HTMLElement>('tr[data-row]')
    if (tr) setFocusedId(Number(tr.dataset.row))
  }
  // Space opens the peek (DataTable would toggle selection); x selects.
  const onKeyDownCapture = (event: KeyboardEvent<HTMLDivElement>) => {
    const target = event.target as HTMLElement
    if (event.key === ' ' && target.matches('tr[data-row]')) {
      event.preventDefault()
      event.stopPropagation()
      setPeek(Number(target.dataset.row))
    }
  }

  const data = list.data
  const groups: RowGroup<ApplicantSummary>[] = []
  if (sort === 'rank') {
    for (const row of view) {
      const last = groups[groups.length - 1]
      if (last && last.id === row.rankGroup) last.rows.push(row)
      else groups.push({ id: row.rankGroup, title: GROUP_COPY[row.rankGroup].title, description: GROUP_COPY[row.rankGroup].description, rows: [row] })
    }
  }

  const columns: Column<ApplicantSummary>[] = [
    {
      key: 'rank',
      header: <span title="Rank within the Ranked group">#</span>,
      width: '44px',
      align: 'end',
      hideWhenStacked: true,
      cell: row => (sort === 'rank' && row.rankGroup === 'RANKED' && row.rank != null
        ? <span className="hra-rank numeric">{row.rank}</span>
        : <span className="subtle"><span aria-hidden="true">—</span><span className="sr-only">Not ranked</span></span>),
    },
    {
      key: 'applicant',
      header: 'Applicant',
      primary: true,
      cell: row => {
        const isNew = previousVisit != null && new Date(row.appliedAt).getTime() > previousVisit
        return (
          <span className="hra-app-cell">
            <span className="hra-app-name">
              <Link
                className="hra-row-link"
                to={`/jobs/${job.id}/applicants/${row.applicationId}`}
                state={{ back: backTo, order }}
              >
                {row.displayName}
              </Link>
              {!row.identityRevealed && <Tag icon={EyeOff} className="hra-blind" title="Identity hidden until shortlisting">Blind</Tag>}
              {isNew && <span className="hra-new">New</span>}
              {row.flags?.length > 0 && (
                <Tooltip content={row.flags.map(code => `${flagInfo(code).label}: ${flagInfo(code).description}`).join(' ')} labelOnly>
                  <span className="hra-flag" role="img" aria-label={`Resume notes: ${row.flags.map(code => flagInfo(code).label).join(', ')}`}>
                    <Icon icon={Info} size={14} />
                  </span>
                </Tooltip>
              )}
            </span>
            <span className="hra-app-sub">
              <time dateTime={row.appliedAt} title={formatDate(row.appliedAt)}>Applied {relativeTime(row.appliedAt)}</time>
              {row.workSampleStatus && <> · Work sample {row.workSampleStatus.toLowerCase().replace(/_/g, ' ')}</>}
              {row.interviewCount > 0 && <> · {plural(row.interviewCount, 'interview')}</>}
            </span>
          </span>
        )
      },
    },
    {
      key: 'status',
      header: 'Status',
      width: '132px',
      cell: row => (
        <span className="hra-status-cell" data-pending={decisions.overrides.get(row.applicationId)?.pending || undefined}>
          <ApplicationStatusBadge status={row.applicationStatus} />
          {decisions.overrides.get(row.applicationId)?.pending && <span className="sr-only">, sending</span>}
        </span>
      ),
    },
    {
      key: 'evidence',
      header: (
        <span className="hra-th-info">
          Evidence by requirement
          <Tooltip content={<RequirementLegend requirements={requirements} />} labelOnly>
            <button type="button" className="hra-info-btn" aria-label={`Requirement order: ${requirements.map(r => r.description).join(', ')}`}>
              <Icon icon={Info} size={12} />
            </button>
          </Tooltip>
        </span>
      ),
      width: `${Math.max(150, requirements.length * 16 + 40)}px`,
      cell: row => (row.matchStatus === 'READY' || row.matchStatus === 'PENDING') && requirements.length > 0 ? (
        <span className="hra-evidence">
          <EvidenceStrip requirements={requirements} detail={details.get(row.applicationId)} unverified={row.unverifiedKnockouts ?? []} pending={row.matchStatus === 'PENDING'} />
          {row.matchStatus === 'PENDING' && <span className="hra-match-note">Matching…</span>}
        </span>
      ) : (
        <span className="hra-match-note" data-status={row.matchStatus}>
          {row.matchStatus === 'FAILED' && <Icon icon={TriangleAlert} size={14} />}
          {MATCH_NOTE[row.matchStatus] || 'No requirements to match against'}
        </span>
      ),
    },
    {
      key: 'verified',
      header: <CoverageHeader label="Verified" />,
      width: '104px',
      cell: row => <CoverageCell value={row.verifiedCoverage} kind="verified" />,
    },
    {
      key: 'claimed',
      header: <CoverageHeader label="With claims" />,
      width: '112px',
      hideWhenStacked: true,
      cell: row => <CoverageCell value={row.claimedCoverage} kind="claimed" />,
    },
    {
      key: 'actions',
      header: <span className="sr-only">Actions</span>,
      width: canDecide ? '76px' : '44px',
      align: 'end',
      cell: row => (
        <span className="hra-row-actions">
          <IconButton label={`Quick look at ${row.displayName} (Space)`} icon={Eye} size="sm" onClick={() => setPeek(row.applicationId)} />
          {canDecide && (
            <Menu
              label={`Decisions for ${row.displayName}`}
              items={[
                { label: 'Open full review', icon: ArrowRight, onSelect: () => openApplicant(row.applicationId) },
                { label: 'Shortlist…', icon: UserRoundCheck, disabled: !canListDecide('SHORTLIST', row.applicationStatus, details.get(row.applicationId)?.allowedActions), onSelect: () => decide('SHORTLIST', [row.applicationId]), separated: true },
                { label: 'Not progressed…', icon: X, danger: true, disabled: !canListDecide('REJECT', row.applicationStatus, details.get(row.applicationId)?.allowedActions), onSelect: () => decide('REJECT', [row.applicationId]) },
              ]}
              trigger={props => <IconButton {...props} label={`More for ${row.displayName}`} icon={Ellipsis} size="sm" noTooltip />}
            />
          )}
        </span>
      ),
    },
  ]

  const statusLabel = status ? (VIEWS.find(v => v.value === status)?.label ?? MORE_VIEWS.find(v => v.value === status)?.label ?? status) : null
  const moreActive = MORE_VIEWS.find(v => v.value === status)
  const selectedRows = view.filter(row => selected.has(row.applicationId))

  const toolbar = (
    <div className="hra-rank-toolbar">
      <div className="hra-rank-views">
        <SegmentedControl<View>
          label="View"
          size="sm"
          value={moreActive ? null : ((status || 'ALL') as View)}
          onChange={value => state.set({ status: value === 'ALL' ? null : value })}
          options={VIEWS}
        />
        <Menu
          label="More views"
          align="start"
          items={MORE_VIEWS.map(v => ({ label: v.label, onSelect: () => state.set({ status: v.value }) }))}
          trigger={props => (
            <Button {...props} size="sm" variant={moreActive ? 'secondary' : 'ghost'} trailingIcon={ChevronDown}>
              {moreActive ? moreActive.label : 'More'}
            </Button>
          )}
        />
        <SegmentedControl<'rank' | 'appliedAt'>
          label="Order"
          size="sm"
          value={sort}
          onChange={value => state.set({ sort: value === 'rank' ? null : value })}
          options={[{ value: 'rank', label: 'Evidence ranking' }, { value: 'appliedAt', label: 'Newest' }]}
        />
      </div>
      <div className="hra-rank-tools">
        <p className="hra-count numeric" aria-live="polite">{data ? plural(data.totalElements, 'applicant') : ''}</p>
        {assistant && (
          <Button size="sm" variant="ghost" icon={MessagesSquare} onClick={() => assistant.ask("Summarise this job's applicants")}>
            Summarise
          </Button>
        )}
        <DensityToggle value={density} onChange={setDensity} />
        <IconButton
          label="Copy link to this view"
          icon={Link2}
          size="sm"
          onClick={() => {
            const url = `${window.location.origin}${location.pathname}${location.search}`
            const failed = () => toast({ tone: 'warning', title: 'Could not copy the link', body: url })
            if (!navigator.clipboard) failed()
            else void navigator.clipboard.writeText(url).then(() => toast({ tone: 'success', title: 'Link to this view copied', duration: 3000 }), failed)
          }}
        />
        {can('applicants.rematch') && (
          <Tooltip content={job.requirements.length === 0 ? 'Add requirements before matching.' : 'Re-run matching for every application of this job.'} labelOnly>
            <Button size="sm" icon={RefreshCw} loading={rematch.isPending} onClick={() => rematch.mutate()} disabled={job.requirements.length === 0}>
              Re-run matching
            </Button>
          </Tooltip>
        )}
      </div>
    </div>
  )

  const bulkBar = (count: number, clear: () => void) => {
    const shortlistable = selectedRows.filter(row => canListDecide('SHORTLIST', row.applicationStatus, details.get(row.applicationId)?.allowedActions))
    const rejectable = selectedRows.filter(row => canListDecide('REJECT', row.applicationStatus, details.get(row.applicationId)?.allowedActions))
    return (
      <div className="hra-bulk">
        <p className="hra-bulk-count numeric"><strong>{formatNumber(count)}</strong> selected</p>
        <Button size="sm" icon={LayoutGrid} disabled={count < 2 || count > 3} onClick={() => setCompare(selectedRows.slice(0, 3).map(row => row.applicationId))}>
          Compare{count > 3 ? ' (pick 2–3)' : ''}
        </Button>
        {canDecide && (
          <>
            <Button size="sm" icon={UserRoundCheck} disabled={!shortlistable.length} onClick={() => decide('SHORTLIST', shortlistable.map(r => r.applicationId))}>
              Shortlist{shortlistable.length && shortlistable.length !== count ? ` ${shortlistable.length}` : ''}
            </Button>
            <Button size="sm" variant="danger-ghost" disabled={!rejectable.length} onClick={() => decide('REJECT', rejectable.map(r => r.applicationId))}>
              Not progressed{rejectable.length && rejectable.length !== count ? ` ${rejectable.length}` : ''}
            </Button>
          </>
        )}
        <Button size="sm" variant="ghost" icon={X} onClick={clear}>Clear</Button>
      </div>
    )
  }

  const emptyState = status ? (
    <EmptyState
      compact
      icon={Search}
      title={`No ${statusLabel?.toLowerCase()} applicants`}
      description="Try another view, or show everyone who applied."
      action={<Button size="sm" onClick={() => state.set({ status: null })}>Show all</Button>}
    />
  ) : (
    <EmptyState
      icon={Users}
      title="No applicants yet"
      description={job.status === 'PUBLISHED'
        ? 'Applications appear here as candidates apply. Each one is matched against the requirements in the background.'
        : job.status === 'DRAFT' ? 'Publish the job to start receiving applications.' : 'This job is not taking applications right now.'}
      action={job.status === 'DRAFT' && can('jobs.manage') ? <Link {...buttonProps('secondary', 'sm')} to={`/jobs/${job.id}/edit?step=review`}>Review and publish</Link> : undefined}
    />
  )

  // Nobody has applied yet: one calm empty state, no tools that cannot do anything.
  if (!status && data && data.totalElements === 0 && !list.isPlaceholderData) {
    return <div className="hra-ranking">{emptyState}</div>
  }

  return (
    <div className="hra-ranking" onFocusCapture={onFocusCapture} onKeyDownCapture={onKeyDownCapture} ref={tableRef}>
      {!hintHidden && (
        <Alert
          tone="neutral"
          className="hra-coverage-hint"
          onDismiss={() => { writeFlag(HINT_FLAG, true); setHintHidden(true) }}
        >
          <strong>Verified</strong> and <strong>With claims</strong> are the share of weighted requirements with evidence (must-haves count double). They are not a probability of success. People make every decision.
        </Alert>
      )}

      {(pendingCount > 0 || poll.timedOut) && (
        <p className="hra-live" role="status">
          {poll.timedOut ? <Icon icon={TriangleAlert} size={14} /> : <Spinner size={14} />}
          <span>{poll.timedOut ? 'Matching is taking longer than usual.' : `Matching ${plural(pendingCount, 'applicant')}… the order updates as results arrive.`}</span>
          {poll.timedOut && <Button size="sm" variant="ghost" icon={RefreshCw} onClick={() => { poll.restart(); void list.refetch() }}>Check again</Button>}
        </p>
      )}

      {list.isError && !data ? (
        <QueryError error={list.error} title="Applicants could not be loaded" onRetry={() => void list.refetch()} retrying={list.isFetching} />
      ) : (
        <div className="hra-fade" data-stale={list.isPlaceholderData || undefined}>
          <DataTable<ApplicantSummary>
            caption={`Applicants for ${job.title}${statusLabel ? `, ${statusLabel.toLowerCase()}` : ''}, ${sort === 'rank' ? 'by evidence ranking' : 'newest first'}`}
            columns={columns}
            rows={sort === 'rank' ? undefined : view}
            groups={sort === 'rank' ? groups : undefined}
            rowKey={row => row.applicationId}
            density={density}
            loading={list.isPending}
            loadingRows={10}
            manualSort
            animateReorder
            selectable
            selected={selected}
            onSelectionChange={next => setSelected(new Set([...next].map(Number)))}
            onRowActivate={row => openApplicant(row.applicationId)}
            activeRowKey={peekId}
            toolbar={toolbar}
            bulkBar={bulkBar}
            empty={emptyState}
            className="hra-rank-table"
          />
        </div>
      )}

      <div className="hra-rank-foot">
        {data && data.totalElements > PAGE_SIZE ? (
          <Pagination page={data.page + 1} pageSize={PAGE_SIZE} total={data.totalElements} onPageChange={next => state.set({ page: next - 1 })} />
        ) : <span />}
        <div className="hra-kbd-hints">
          <span aria-hidden="true">
            <Kbd>j</Kbd><Kbd>k</Kbd> move <Kbd>Enter</Kbd> open <Kbd>Space</Kbd> peek <Kbd>x</Kbd> select
            {canDecide && <> <Kbd>s</Kbd> shortlist <Kbd>r</Kbd> not progressed</>}
          </span>
          <button type="button" className="hra-link-inline" onClick={() => setHelpOpen(true)}><Kbd>?</Kbd> Keyboard shortcuts</button>
        </div>
      </div>

      <ApplicantPeek
        job={job}
        applicationId={peekId}
        summary={view.find(row => row.applicationId === peekId)}
        position={peekId != null ? { index: order.indexOf(peekId), total: order.length } : null}
        onClose={() => {
          const id = peekId
          setPeek(null)
          if (id != null) window.setTimeout(() => focusRow(id), 0)
        }}
        onMove={moveFocus}
        onOpen={() => peekId != null && openApplicant(peekId)}
        onDecide={canDecide ? action => peekId != null && decide(action, [peekId]) : undefined}
        canDecideAction={action => {
          const row = view.find(r => r.applicationId === peekId)
          return !!row && canListDecide(action, row.applicationStatus, details.get(row.applicationId)?.allowedActions)
        }}
      />

      <ApplicantCompare
        job={job}
        ids={compareIds}
        summaries={view}
        onClose={() => setCompare(null)}
        onOpen={id => openApplicant(id)}
      />

      {decisions.dialog}

      <ShortcutSheet
        open={helpOpen}
        onClose={() => setHelpOpen(false)}
        groups={[
          { title: 'Move', items: [{ keys: ['j', '↓'], label: 'Next applicant' }, { keys: ['k', '↑'], label: 'Previous applicant' }, { keys: ['Enter'], label: 'Open the full review' }, { keys: ['Space'], label: 'Quick look (and close it)' }] },
          { title: 'Act', items: [
            { keys: ['x'], label: 'Select or unselect' },
            { keys: ['c'], label: 'Compare the selected 2–3' },
            ...(canDecide ? [{ keys: ['s'], label: 'Shortlist (asks first, then 5 s to undo)' }, { keys: ['r'], label: 'Not progressed (asks first, then 5 s to undo)' }] : []),
          ] },
          { title: 'Anywhere', items: [{ keys: ['?'], label: 'Show these shortcuts' }, { keys: ['Esc'], label: 'Close a drawer or dialog' }] },
        ]}
      />
    </div>
  )
}

function CoverageHeader({ label }: { label: string }) {
  return (
    <span className="hra-th-info">
      {label}
      <Tooltip content={COVERAGE_NOTE} labelOnly>
        <button type="button" className="hra-info-btn" aria-label={`About ${label.toLowerCase()} coverage: ${COVERAGE_NOTE}`}>
          <Icon icon={Info} size={12} />
        </button>
      </Tooltip>
    </span>
  )
}
