import { Fragment } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useLocation } from 'react-router-dom'
import { errorMessage } from '../../api/client'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import { APPLICATION_STATUSES, ApplicantListParams, ApplicantSummary, ApplicationStatus, JobStaff, RankGroup } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { FlagChips, StatusBadge } from '../../components/Badges'
import { CoverageBars } from '../../components/CoverageBars'
import { RefreshIcon } from '../../components/Icons'
import { useNotices } from '../../components/Notices'
import { Pagination } from '../../components/Pagination'
import { EmptyPanel, ErrorPanel, LoadingPanel } from '../../components/States'
import { formatDate, humanize } from '../../lib/format'
import { usePendingPoll, usePollRef } from '../../lib/hooks'
import { useSearchState } from '../../lib/useSearchState'

const PAGE_SIZE = 25

const GROUPS: Record<RankGroup, { title: string; description: string }> = {
  RANKED: {
    title: 'Ranked',
    description: 'Every ranking-gate requirement is verified. Ordered by verified coverage, then claimed coverage, then application date.',
  },
  MUST_HAVES_UNVERIFIED: {
    title: 'Must-haves not yet verified',
    description: 'At least one ranking-gate requirement is not verified yet. Not rejected — check the evidence, a claim, a work sample or an interview.',
  },
  UNSCORED: {
    title: 'Unscored',
    description: 'No match result yet: the resume is still being analysed, could not be read, or the job has no requirements.',
  },
}

const MATCH_LABELS: Record<ApplicantSummary['matchStatus'], string> = {
  READY: '',
  PENDING: 'Matching…',
  NO_REQUIREMENTS: 'No requirements to match against',
  ANALYSIS_UNAVAILABLE: 'Resume not analysed yet',
  FAILED: 'Matching failed',
}

/**
 * Server-ranked applicant list (C2). Ranking is computed on the server across all pages; this view groups the
 * current page by rank group and shows a rank number only for RANKED applicants. Polls only while some applicant
 * is still matching (L8); the query key carries every filter so stale pages never render (L7).
 */
export function ApplicantsTab({ job }: { job: JobStaff }) {
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const notices = useNotices()
  const state = useSearchState()
  const location = useLocation()
  const backTo = `${location.pathname}${location.search}`
  const status = state.get('status') as ApplicationStatus | ''
  const sort = state.get('sort') === 'appliedAt' ? 'appliedAt' : 'rank'
  const page = state.getPage()
  const params: ApplicantListParams = { page, size: PAGE_SIZE, status: status || undefined, sort }

  const listKey = JSON.stringify([job.id, params])
  const pollRef = usePollRef()
  const listQuery = useQuery({
    queryKey: qk.applicantList(job.id, params),
    queryFn: ({ signal }) => reviewApi.list(job.id, params, signal),
    refetchInterval: pollRef.interval,
  })
  const pending = listQuery.data?.content.some(applicant => applicant.matchStatus === 'PENDING') ?? false
  const poll = usePendingPoll(pending, listKey)
  pollRef.bind(poll.refetchInterval)

  const rematch = useMutation({
    mutationFn: () => reviewApi.rematch(job.id),
    onSuccess: result => {
      notices.success(result.enqueued === 0 ? 'Nothing to re-match: no applications are waiting for analysis.' : `Re-matching ${result.enqueued} application${result.enqueued === 1 ? '' : 's'}. Results appear here as they finish.`)
      poll.restart()
      void queryClient.invalidateQueries({ queryKey: qk.applicants(job.id) })
    },
    onError: cause => notices.error(errorMessage(cause, 'Re-matching could not be started.')),
  })

  const data = listQuery.data
  const groups: { group: RankGroup; items: ApplicantSummary[] }[] = []
  for (const applicant of data?.content ?? []) {
    const last = groups[groups.length - 1]
    if (last && last.group === applicant.rankGroup) last.items.push(applicant)
    else groups.push({ group: applicant.rankGroup, items: [applicant] })
  }

  return (
    <section className="jobs-card" aria-labelledby="applicants-heading">
      <div className="jobs-card-head">
        <div>
          <h2 id="applicants-heading">Applicants</h2>
          <p>Coverage is the share of weighted requirements with evidence — not a probability of success. Humans make every decision.</p>
        </div>
        <div className="head-actions">
          {pending && <span className="live-pill" role="status"><span className="spinner dark-spinner small" aria-hidden="true" /> {poll.timedOut ? 'Still matching' : 'Matching in progress'}</span>}
          {poll.timedOut && <button type="button" className="outline-button" onClick={() => { poll.restart(); void listQuery.refetch() }}><RefreshIcon /> Check again</button>}
          {can('applicants.rematch') && (
            <button type="button" className="outline-button" onClick={() => rematch.mutate()} disabled={rematch.isPending || job.requirements.length === 0} title={job.requirements.length === 0 ? 'Add requirements before matching.' : 'Re-run matching for every application of this job.'}>
              <RefreshIcon /> {rematch.isPending ? 'Starting…' : 'Re-run matching'}
            </button>
          )}
        </div>
      </div>

      <div className="jobs-toolbar">
        <label className="status-filter">
          <span>Status</span>
          <select value={status} onChange={event => state.set({ status: event.target.value })}>
            <option value="">All statuses</option>
            {APPLICATION_STATUSES.map(value => <option key={value} value={value}>{humanize(value)}</option>)}
          </select>
        </label>
        <label className="status-filter">
          <span>Order</span>
          <select value={sort} onChange={event => state.set({ sort: event.target.value === 'rank' ? null : event.target.value })}>
            <option value="rank">Evidence ranking</option>
            <option value="appliedAt">Application date</option>
          </select>
        </label>
        <span className="record-count toolbar-count" aria-live="polite">{data ? `${data.totalElements} applicant${data.totalElements === 1 ? '' : 's'}` : '—'}</span>
      </div>

      {listQuery.isPending ? (
        <LoadingPanel label="Loading applicants…" />
      ) : listQuery.isError ? (
        <ErrorPanel error={listQuery.error} title="We couldn’t load applicants" onRetry={() => void listQuery.refetch()} />
      ) : data && data.content.length === 0 ? (
        <EmptyPanel title={status ? `No ${humanize(status).toLowerCase()} applicants` : 'No applicants yet'}>
          {status ? 'Try another status.' : job.status === 'PUBLISHED' ? 'Applications appear here as candidates apply.' : 'Publish the job to start receiving applications.'}
        </EmptyPanel>
      ) : (
        <>
          <div className="applicant-groups">
            {groups.map((group, index) => (
              <Fragment key={`${group.group}-${index}`}>
                {sort === 'rank' && (
                  <div className={`group-heading group-${group.group.toLowerCase()}`}>
                    <h3>{GROUPS[group.group].title} <span>({group.items.length} on this page)</span></h3>
                    <p>{GROUPS[group.group].description}</p>
                  </div>
                )}
                <ol className="applicant-list">
                  {group.items.map(applicant => <ApplicantRow key={applicant.applicationId} jobId={job.id} applicant={applicant} showRank={sort === 'rank'} backTo={backTo} />)}
                </ol>
              </Fragment>
            ))}
          </div>
          <Pagination page={data} onPage={next => state.set({ page: next })} label="applicants" />
        </>
      )}
    </section>
  )
}

function ApplicantRow({ jobId, applicant, showRank, backTo }: { jobId: number; applicant: ApplicantSummary; showRank: boolean; backTo: string }) {
  const matchNote = MATCH_LABELS[applicant.matchStatus]
  const unverified = applicant.unverifiedKnockouts?.length ?? 0
  return (
    <li className={`applicant-row group-${applicant.rankGroup.toLowerCase()}`}>
      <div className="applicant-rank" aria-label={applicant.rank != null && showRank ? `Rank ${applicant.rank}` : 'Not ranked'}>
        {showRank && applicant.rankGroup === 'RANKED' && applicant.rank != null ? <span>#{applicant.rank}</span> : <span className="no-rank" aria-hidden="true">—</span>}
      </div>
      <div className="applicant-identity">
        <Link className="applicant-name" to={`/jobs/${jobId}/applicants/${applicant.applicationId}`} state={{ back: backTo }}>
          {applicant.displayName}
        </Link>
        <div className="applicant-meta">
          <StatusBadge status={applicant.applicationStatus} />
          {!applicant.identityRevealed && <span className="blind-pill" title="Identity hidden until shortlisting">Blind</span>}
          <span>Applied {formatDate(applicant.appliedAt)}</span>
          {applicant.workSampleStatus && <span>Work sample: {humanize(applicant.workSampleStatus).toLowerCase()}</span>}
          {applicant.interviewCount > 0 && <span>{applicant.interviewCount} interview{applicant.interviewCount === 1 ? '' : 's'}</span>}
        </div>
        <FlagChips flags={applicant.flags ?? []} />
      </div>
      <div className="applicant-coverage">
        {applicant.matchStatus === 'READY' ? (
          <>
            <CoverageBars verified={applicant.verifiedCoverage} claimed={applicant.claimedCoverage} compact />
            {unverified > 0 && <span className="gate-note">{unverified} ranking gate{unverified === 1 ? '' : 's'} not yet verified</span>}
          </>
        ) : (
          <span className={`match-note match-${applicant.matchStatus.toLowerCase()}`}>
            {applicant.matchStatus === 'PENDING' && <span className="spinner dark-spinner small" aria-hidden="true" />}
            {matchNote}
          </span>
        )}
      </div>
      <div className="applicant-open">
        <Link className="row-action" to={`/jobs/${jobId}/applicants/${applicant.applicationId}`} state={{ back: backTo }} aria-label={`Review ${applicant.displayName}`}>Review →</Link>
      </div>
    </li>
  )
}
