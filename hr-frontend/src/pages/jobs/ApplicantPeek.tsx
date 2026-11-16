import { useQuery } from '@tanstack/react-query'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { ApplicantSummary, JobStaff, RequirementEvidence } from '../../api/types'
import { QueryError } from '../../components/QueryError'
import { ApplicationStatusBadge } from '../../components/StatusBadges'
import {
  Button, CoverageMeter, Drawer, EvidenceLadder, IconButton, ReqMarker, Skeleton, SkeletonText, Tag,
} from '../../design/components'
import { plural, relativeTime } from '../../design/lib/format'
import { ArrowRight, ChevronDown, ChevronUp, EyeOff, Icon, LockOpen, UserRoundCheck, X } from '../../design/icons'
import { flagInfo } from '../../lib/format'
import { GROUP_COPY, stripRequirements } from './ranking'
import { Kbd } from '../../components/Shortcuts'

const STATUS_WORD: Record<RequirementEvidence['status'], string> = { MATCHED: 'Matched', UNCLEAR: 'Unclear', NOT_OBSERVED: 'Not observed' }

/**
 * Quick look (`?peek=<applicationId>`): the evidence behind one row without leaving the list. j/k move to the next
 * applicant inside the drawer; Open full review is the primary; managers can shortlist or mark not progressed.
 */
export function ApplicantPeek({ job, applicationId, summary, position, onClose, onMove, onOpen, onDecide, canDecideAction }: {
  job: JobStaff
  applicationId: number | null
  summary?: ApplicantSummary
  position: { index: number; total: number } | null
  onClose: () => void
  onMove: (delta: number) => void
  onOpen: () => void
  onDecide?: (action: 'SHORTLIST' | 'REJECT') => void
  canDecideAction: (action: 'SHORTLIST' | 'REJECT') => boolean
}) {
  const open = applicationId != null
  const detail = useQuery({
    queryKey: qk.applicant(job.id, applicationId ?? 0),
    queryFn: ({ signal }) => reviewApi.detail(job.id, applicationId as number, signal),
    enabled: open,
    staleTime: 60_000,
  })
  const d = detail.data
  const name = d?.displayName ?? summary?.displayName ?? 'Applicant'
  const unverified = d?.unverifiedKnockouts ?? summary?.unverifiedKnockouts ?? []
  const order = stripRequirements(job.requirements)
  const others = job.requirements.filter(r => r.type === 'OTHER')
  const evidence = (id: number) => d?.requirements.find(e => e.requirementId === id)

  return (
    <Drawer
      open={open}
      onClose={onClose}
      size="lg"
      className="hra-peek"
      title={
        <span className="hra-peek-title">
          <span>{name}</span>
          {d && !d.identityRevealed && <Tag icon={EyeOff}>Blind</Tag>}
        </span>
      }
      description={summary || d ? (
        <span className="hra-peek-meta">
          <ApplicationStatusBadge status={(summary ?? d)!.applicationStatus} />
          <span>{(summary ?? d)!.rankGroup === 'RANKED' && (summary ?? d)!.rank != null ? `Rank ${(summary ?? d)!.rank}` : GROUP_COPY[(summary ?? d)!.rankGroup].title}</span>
          <span>Applied {relativeTime((summary ?? d)!.appliedAt)}</span>
        </span>
      ) : undefined}
      headerExtra={position && position.index >= 0 ? (
        <div className="hra-peek-nav">
          <span className="numeric subtle">{position.index + 1} of {position.total}</span>
          <IconButton label="Previous applicant (k)" icon={ChevronUp} size="sm" disabled={position.index <= 0} onClick={() => onMove(-1)} />
          <IconButton label="Next applicant (j)" icon={ChevronDown} size="sm" disabled={position.index >= position.total - 1} onClick={() => onMove(1)} />
        </div>
      ) : undefined}
      footer={
        <div className="hra-peek-footer">
          {onDecide && (
            <div className="hra-peek-decide">
              <Button size="md" icon={UserRoundCheck} disabled={!canDecideAction('SHORTLIST')} onClick={() => onDecide('SHORTLIST')}>Shortlist</Button>
              <Button size="md" variant="danger-ghost" icon={X} disabled={!canDecideAction('REJECT')} onClick={() => onDecide('REJECT')}>Not progressed</Button>
            </div>
          )}
          <Button variant="primary" trailingIcon={ArrowRight} onClick={onOpen}>Open full review</Button>
        </div>
      }
    >
      {detail.isError ? (
        <QueryError error={detail.error} title="This applicant could not be loaded" onRetry={() => void detail.refetch()} compact />
      ) : !d ? (
        <div className="hra-peek-body" aria-busy="true">
          <Skeleton width="60%" height={14} />
          <Skeleton width="100%" height={40} />
          {Array.from({ length: 5 }, (_, i) => <SkeletonText key={i} lines={2} />)}
        </div>
      ) : (
        <div className="hra-peek-body" key={d.applicationId}>
          <section className="hra-peek-cov" aria-label="Coverage">
            {d.matchStatus === 'READY' && d.verifiedCoverage != null ? (
              <CoverageMeter verified={d.verifiedCoverage} claimed={d.claimedCoverage ?? d.verifiedCoverage} />
            ) : (
              <p className="subtle">{d.matchStatus === 'PENDING' ? 'Matching is running; coverage appears when it finishes.' : 'No coverage yet for this applicant.'}</p>
            )}
            {unverified.length > 0 && (
              <p className="hra-peek-gate">
                <Icon icon={LockOpen} size={14} />
                {plural(unverified.length, 'gate')} not yet verified. Not rejected: verify a claim, assign a work sample or ask in the interview.
              </p>
            )}
          </section>

          <section aria-labelledby="peek-req">
            <h3 id="peek-req" className="t-label hra-peek-h">Evidence by requirement</h3>
            <ol className="hra-peek-reqs">
              {order.map(r => {
                const e = evidence(r.id)
                const gateOpen = unverified.includes(r.id)
                const tier = e && e.status !== 'NOT_OBSERVED' ? e.tier : null
                return (
                  <li key={r.id} className="hra-peek-req" data-gate={gateOpen || undefined}>
                    <div className="hra-peek-req-head">
                      <span className="hra-peek-req-text">{r.description}</span>
                      <EvidenceLadder tier={tier} variant="chip" gateOpen={gateOpen} />
                    </div>
                    <p className="hra-peek-req-meta">
                      <ReqMarker kind={r.mandatory && r.knockout ? 'gate' : r.mandatory ? 'must' : 'nice'} />
                      {e && <span>{STATUS_WORD[e.status]}</span>}
                    </p>
                    {e?.evidence && <blockquote className="ui-quote hra-peek-quote">{e.evidence}</blockquote>}
                    {e?.explanation && <p className="hra-peek-expl">{e.explanation}</p>}
                    {e?.verificationHint && <p className="hra-peek-hint"><strong>To verify:</strong> {e.verificationHint}</p>}
                  </li>
                )
              })}
            </ol>
            {others.length > 0 && <p className="subtle hra-peek-note">{plural(others.length, '“Other” requirement')} not scored automatically: reviewed by people on the full page.</p>}
          </section>

          <section aria-labelledby="peek-more" className="hra-peek-facts">
            <h3 id="peek-more" className="t-label hra-peek-h">Also on file</h3>
            <dl>
              <dt>Claims</dt><dd>{d.claims.length ? plural(d.claims.length, 'claim') : 'None'}</dd>
              <dt>Work samples</dt><dd>{d.workSamples.length ? d.workSamples.map(w => `${w.workSampleTitle} (${w.status.toLowerCase()}${w.totalScore != null ? `, ${w.totalScore.toFixed(1)} / 4` : ''})`).join('; ') : 'None assigned'}</dd>
              <dt>Interviews</dt><dd>{d.interviews.length ? plural(d.interviews.length, 'interview') : 'None scheduled'}</dd>
              {d.flags.length > 0 && <><dt>Resume notes</dt><dd>{d.flags.map(code => flagInfo(code).label).join(', ')}</dd></>}
            </dl>
          </section>
          <p className="hra-peek-keys subtle" aria-hidden="true"><Kbd>j</Kbd> <Kbd>k</Kbd> next and previous · <Kbd>Esc</Kbd> back to the list</p>
        </div>
      )}
    </Drawer>
  )
}
