import { useId, useState, type FormEvent } from 'react'
import { useMutation, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { qk } from '../../api/queryKeys'
import type { ReviewDecision, StaffClaim, StaffVerdict, StaffVerification } from '../../api/types'
import { verificationApi } from '../../api/verification'
import { useAuth } from '../../auth/AuthContext'
import { QueryError, InlineError } from '../../components/QueryError'
import { DomainTrustChip, OwnershipLine, SOURCE_ICON, VerdictDetails } from '../../components/VerdictParts'
import {
  Alert, Button, EmptyState, EvidenceLadder, Field, LoadingRegion, ProvenanceLine, SegmentedControl, Skeleton, SkeletonText, Textarea, VerdictChip, buttonProps, useToast,
} from '../../design/components'
import { BadgeCheck, ChevronDown, CircleCheck, Icon, TriangleAlert } from '../../design/icons'
import { plural, relativeTime } from '../../design/lib/format'
import type { Verdict } from '../../design/lib/labels'
import { humanize } from '../../lib/format'
import { reviewProblem, sourceKindLabel } from '../../lib/verification'
import { asTier } from './model'

export const SHADOW_MESSAGE = 'Verification is in shadow mode for your organisation; results are hidden until an admin switches it on.'

const needsReview = (c: StaffClaim) => c.verdicts.some(v => !v.myOrgReview)

/**
 * Applicant Verification tab (FLOWS-HR-B §4): verdicts the candidate chose to share, one row per claim with this
 * organisation's tier ladder and the verdict chips; expanding a row shows the proof and an inline Confirm / Overrule.
 * Blind-first: before reveal only `evidenceCount` is shown.
 */
export function VerificationTab({ jobId, applicationId, query }: { jobId: number; applicationId: number; query: UseQueryResult<StaffVerification> }) {
  if (query.isPending) {
    return (
      <LoadingRegion label="Loading verification results">
        <div className="vt-skel">{Array.from({ length: 3 }, (_, i) => <div key={i} className="ap-skel-row"><SkeletonText lines={2} /><Skeleton width={90} height={14} /><Skeleton width={120} height={22} /></div>)}</div>
      </LoadingRegion>
    )
  }
  if (query.isError) return <QueryError error={query.error} title="We couldn’t load verification results" onRetry={() => void query.refetch()} retrying={query.isFetching} />
  return <VerificationView jobId={jobId} applicationId={applicationId} data={query.data} />
}

export function VerificationView({ jobId, applicationId, data }: { jobId: number; applicationId: number; data: StaffVerification }) {
  const { can } = useAuth()
  const [filter, setFilter] = useState<'all' | 'review'>('all')
  const [open, setOpen] = useState<Set<string>>(() => {
    const first = data.claims.find(needsReview) ?? data.claims[0]
    return new Set(first ? [`${first.subjectType}-${first.subjectId}`] : [])
  })

  if (data.mode === 'SHADOW') {
    return (
      <div className="vt">
        <Alert
          tone="neutral"
          title="Shadow mode"
          action={can('verification.queue') ? <Link {...buttonProps('secondary', 'sm')} to="/admin/verification">Verification settings</Link> : undefined}
        >
          {SHADOW_MESSAGE} Agents still check shared sources in the background; nothing here affects the ranking.
        </Alert>
      </div>
    )
  }

  if (data.claims.length === 0) {
    return (
      <EmptyState
        icon={BadgeCheck}
        title="No shared verification results"
        description="The candidate has not shared results, or none came from a connector your organisation enabled. This says nothing for or against the candidate."
      />
    )
  }

  const pending = data.claims.filter(needsReview)
  const shown = filter === 'review' ? pending : data.claims
  const verdictCount = data.claims.reduce((n, c) => n + c.verdicts.length, 0)

  return (
    <div className="vt">
      <div className="ap-tab-head">
        <p className="ap-tab-intro">
          {plural(verdictCount, 'result')} the candidate chose to share, on {plural(data.claims.length, 'claim')}. Reasons are de-identified.
          {data.revealed ? ' Sources are shown because the identity is revealed.' : ' Sources stay hidden until the identity is revealed.'}
        </p>
        <div className="ap-tab-actions">
          <SegmentedControl
            label="Show"
            size="sm"
            value={filter}
            onChange={v => setFilter(v as 'all' | 'review')}
            options={[{ value: 'all', label: 'All' }, { value: 'review', label: `Needs review · ${pending.length}` }]}
          />
        </div>
      </div>

      {shown.length === 0 ? (
        <EmptyState compact icon={CircleCheck} title="Everything here is reviewed" description="Your organisation has confirmed or overruled every shared result." action={<Button size="sm" variant="secondary" onClick={() => setFilter('all')}>Show all</Button>} />
      ) : (
        <ul className="vt-claims">
          {shown.map(claim => {
            const key = `${claim.subjectType}-${claim.subjectId}`
            return (
              <ClaimRow
                key={key}
                claim={claim}
                open={open.has(key)}
                onToggle={() => setOpen(cur => { const n = new Set(cur); if (n.has(key)) n.delete(key); else n.add(key); return n })}
                jobId={jobId}
                applicationId={applicationId}
                revealed={data.revealed}
              />
            )
          })}
        </ul>
      )}
    </div>
  )
}

function ClaimRow({ claim, open, onToggle, jobId, applicationId, revealed }: {
  claim: StaffClaim
  open: boolean
  onToggle: () => void
  jobId: number
  applicationId: number
  revealed: boolean
}) {
  const bodyId = useId()
  const reviewed = claim.verdicts.filter(v => v.myOrgReview)
  const state = reviewed.length === claim.verdicts.length
    ? (claim.verdicts.every(v => v.myOrgReview?.decision === 'CONFIRM') ? 'Confirmed' : claim.verdicts.some(v => v.myOrgReview?.decision === 'OVERRULE') ? 'Overruled' : 'Reviewed')
    : 'Needs review'
  return (
    <li className="vt-claim" data-open={open || undefined}>
      <div className="vt-claim-head">
        <div className="vt-claim-name">
          <span className="vt-claim-kind">{humanize(claim.claimType)} · {claim.subjectType === 'CANDIDATE_CLAIM' ? 'candidate claim' : 'from the resume'}</span>
          <button type="button" className="ap-req-toggle" aria-expanded={open} aria-controls={bodyId} onClick={onToggle}>{claim.value}</button>
        </div>
        <div className="vt-claim-tier"><EvidenceLadder tier={asTier(claim.tier)} audience="staff" /></div>
        <div className="vt-claim-verdicts">
          {claim.verdicts.map(v => <VerdictChip key={v.id} verdict={v.verdict as Verdict} size="sm" shortLabel={v.verdict === 'NOT_FOUND' ? 'Not found' : v.verdict === 'NOT_CHECKABLE' ? 'Not checkable' : undefined} />)}
        </div>
        <div className="vt-claim-state" data-state={state}>
          {state === 'Needs review' ? <span className="vt-dot" aria-hidden="true" /> : <Icon icon={state === 'Overruled' ? TriangleAlert : CircleCheck} size={14} />}
          {state}
        </div>
        <Icon icon={ChevronDown} size={16} className="ap-req-chevron" />
      </div>
      <div className="ap-req-body" id={bodyId} aria-hidden={!open || undefined} {...(open ? {} : ({ inert: '' } as Record<string, string>))}>
        <div><div className="vt-verdicts">
          {claim.verdicts.map(v => <VerdictBlock key={v.id} verdict={v} claim={claim} jobId={jobId} applicationId={applicationId} revealed={revealed} />)}
        </div></div>
      </div>
    </li>
  )
}

function VerdictBlock({ verdict, claim, jobId, applicationId, revealed }: { verdict: StaffVerdict; claim: StaffClaim; jobId: number; applicationId: number; revealed: boolean }) {
  return (
    <section className="vt-verdict" aria-label={`${sourceKindLabel(verdict.sourceKind)} result`}>
      <header className="vt-verdict-head">
        <VerdictChip verdict={verdict.verdict as Verdict} />
        <OwnershipLine proven={verdict.ownershipProven} />
        {verdict.domainTrust && <DomainTrustChip trust={verdict.domainTrust} />}
        <ProvenanceLine
          className="vt-prov"
          icon={verdict.sourceKind ? SOURCE_ICON[verdict.sourceKind] : undefined}
          source={sourceKindLabel(verdict.sourceKind)}
          actor={verdict.verifier}
          at={verdict.createdAt}
        />
      </header>
      <VerdictDetails verdict={verdict} revealed={revealed} />
      <ReviewPanel verdict={verdict} claim={claim} jobId={jobId} applicationId={applicationId} />
    </section>
  )
}

/**
 * Confirm / Overrule inline (no dialog). The note is optional for Confirm and required for Overrule (the server answers
 * 400 otherwise). After a save the server re-matches; when the tier lifts, the ladder above animates in place.
 */
function ReviewPanel({ verdict, claim, jobId, applicationId }: { verdict: StaffVerdict; claim: StaffClaim; jobId: number; applicationId: number }) {
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const review = verdict.myOrgReview
  const [editing, setEditing] = useState(!review)
  const [decision, setDecision] = useState<ReviewDecision | null>(null)
  const [note, setNote] = useState('')
  const [problem, setProblem] = useState<string | null>(null)
  const noteId = useId()
  const lifts = verdict.verdict === 'VERIFIED' && verdict.ownershipProven

  const save = useMutation({
    mutationFn: () => verificationApi.review(verdict.id, { decision: decision!, note: note.trim() || undefined }),
    onSuccess: updated => {
      queryClient.setQueryData<StaffVerification>(qk.applicantVerification(applicationId), current => current && {
        ...current,
        claims: current.claims.map(c => c !== claim && !(c.subjectId === claim.subjectId && c.subjectType === claim.subjectType) ? c : {
          ...c,
          verdicts: c.verdicts.map(v => (v.id === verdict.id ? { ...v, ...updated } : v)),
        }),
      })
      setEditing(false)
      setNote('')
      toast({
        tone: 'success',
        title: decision === 'CONFIRM' ? 'Result confirmed' : 'Result overruled',
        body: decision === 'CONFIRM' && lifts ? 'The claim rises to Corroborated for your organisation once matching finishes.' : 'Matching is recalculated for this candidate.',
      })
      void queryClient.invalidateQueries({ queryKey: qk.applicantVerification(applicationId) })
      void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, applicationId) })
      void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const found = reviewProblem(decision, note)
    setProblem(found)
    if (found) {
      document.getElementById(decision ? noteId : `${noteId}-seg`)?.querySelector<HTMLElement>('input, textarea')?.focus()
      if (decision) document.getElementById(noteId)?.focus()
      return
    }
    save.mutate()
  }

  return (
    <div className="vt-review">
      <h4 className="t-label">Your organisation’s review</h4>
      {review && !editing ? (
        <div className="vt-review-done" data-decision={review.decision}>
          <p>
            <Icon icon={review.decision === 'CONFIRM' ? CircleCheck : TriangleAlert} size={16} />
            <strong>{review.decision === 'CONFIRM' ? 'Confirmed' : 'Overruled'}</strong>
            <span className="ap-muted"> by {review.reviewerName ?? 'a former team member'} · {relativeTime(review.at)}</span>
          </p>
          {review.note && <p className="vt-review-note">“{review.note}”</p>}
          {can('verification.review') && <Button size="sm" variant="ghost" onClick={() => { setDecision(review.decision); setEditing(true) }}>Change review</Button>}
        </div>
      ) : can('verification.review') ? (
        <form className="vt-review-form" onSubmit={submit} noValidate>
          <div id={`${noteId}-seg`}>
            <SegmentedControl
              label="Decision"
              value={decision}
              onChange={v => { setDecision(v as ReviewDecision); setProblem(null) }}
              options={[
                { value: 'CONFIRM', label: 'Confirm', icon: CircleCheck },
                { value: 'OVERRULE', label: 'Overrule', icon: TriangleAlert },
              ]}
            />
          </div>
          <p className="vt-review-hint">
            {decision === 'OVERRULE' ? 'Overruling keeps the tier where it was. Say what you see differently.'
              : lifts ? 'Confirming lifts this claim to Corroborated for your organisation only.'
                : 'Confirming records your agreement. Only a verified result with proven ownership lifts the tier.'}
          </p>
          {decision && (
            <Field label="Note" optional={decision !== 'OVERRULE'} error={decision ? problem : null}>
              <Textarea
                id={noteId}
                rows={2}
                maxRows={6}
                maxLength={1000}
                value={note}
                placeholder={decision === 'OVERRULE' ? 'Why do you disagree? Kept with the review.' : 'Optional context for your team'}
                onChange={e => { setNote(e.target.value); if (problem) setProblem(null) }}
              />
            </Field>
          )}
          {!decision && problem && <InlineError error={problem} />}
          <InlineError error={save.error} />
          <div className="vt-review-actions">
            {review && <Button size="sm" variant="ghost" onClick={() => { setEditing(false); setProblem(null) }} disabled={save.isPending}>Cancel</Button>}
            <Button size="sm" type="submit" variant="primary" loading={save.isPending}>Save review</Button>
          </div>
        </form>
      ) : (
        <p className="ap-muted">Not reviewed by your organisation yet.</p>
      )}
    </div>
  )
}
