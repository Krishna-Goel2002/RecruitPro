import { FormEvent, useEffect, useId, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { qk } from '../../api/queryKeys'
import type { EvidenceTier, ReviewDecision, StaffClaim, StaffVerdict, StaffVerification } from '../../api/types'
import { EVIDENCE_TIERS } from '../../api/types'
import { verificationApi } from '../../api/verification'
import { useAuth } from '../../auth/AuthContext'
import { TierBadge } from '../../components/Badges'
import { Dialog } from '../../components/Dialog'
import { useNotices } from '../../components/Notices'
import { EmptyPanel, ErrorPanel, FormError, LoadingPanel } from '../../components/States'
import { OwnershipIndicator, VerdictBody, VerdictChip } from '../../components/Verification'
import { formatDateTime, humanize } from '../../lib/format'
import { DECISION_LABELS, reviewProblem, sourceKindLabel, verdictInfo } from '../../lib/verification'

export const SHADOW_MESSAGE = 'Verification is in shadow mode for your organisation; results are hidden until an admin switches it on.'

/**
 * Applicant "Verification" tab (WP-VBE `GET /hr/applications/{id}/verification`): verdicts the candidate chose to
 * share, with de-identified reasons, integrity notes, a suggested interview question, evidence after reveal, and this
 * organisation's Confirm / Overrule review.
 */
export function VerificationTab({ jobId, applicationId }: { jobId: number; applicationId: number }) {
  const view = useQuery({
    queryKey: qk.applicantVerification(applicationId),
    queryFn: ({ signal }) => verificationApi.forApplication(applicationId, signal),
  })
  if (view.isPending) return <LoadingPanel label="Loading verification results…" />
  if (view.isError) return <ErrorPanel error={view.error} title="We couldn’t load verification results" onRetry={() => void view.refetch()} />
  return <VerificationView jobId={jobId} applicationId={applicationId} data={view.data} />
}

export function VerificationView({ jobId, applicationId, data }: { jobId: number; applicationId: number; data: StaffVerification }) {
  if (data.mode === 'SHADOW') {
    return (
      <section className="detail-card section-card" aria-labelledby="verification-heading">
        <h2 id="verification-heading">Verification</h2>
        <p className="mode-banner shadow" role="note"><strong>Shadow mode.</strong> {SHADOW_MESSAGE}</p>
      </section>
    )
  }
  return (
    <section className="detail-card section-card" aria-labelledby="verification-heading">
      <div className="section-head">
        <div>
          <h2 id="verification-heading">Verification</h2>
          <p>
            Results from sources the candidate connected and chose to share. Reasons are de-identified.
            {data.revealed ? ' Evidence sources are shown because the identity is revealed.' : ' Sources stay hidden until the candidate is revealed.'}
          </p>
        </div>
      </div>
      {data.claims.length === 0 ? (
        <EmptyPanel compact title="No shared verification results">
          The candidate has not shared any verification results, or none of the connectors enabled for your organisation produced one.
          This says nothing for or against the candidate.
        </EmptyPanel>
      ) : (
        <ul className="card-list verification-claims">
          {data.claims.map(claim => (
            <ClaimCard key={`${claim.subjectType}-${claim.subjectId}`} claim={claim} jobId={jobId} applicationId={applicationId} revealed={data.revealed} />
          ))}
        </ul>
      )}
    </section>
  )
}

function asTier(value: string | null): EvidenceTier | null {
  return value && (EVIDENCE_TIERS as string[]).includes(value) ? (value as EvidenceTier) : null
}

function ClaimCard({ claim, jobId, applicationId, revealed }: { claim: StaffClaim; jobId: number; applicationId: number; revealed: boolean }) {
  const headingId = useId()
  return (
    <li className="item-card claim-card" aria-labelledby={headingId}>
      <div className="item-card-head">
        <div>
          <p className="eyebrow">{humanize(claim.claimType)} · {claim.subjectType === 'CANDIDATE_CLAIM' ? 'Candidate claim' : 'From the resume'}</p>
          <h3 id={headingId}>{claim.value}</h3>
        </div>
        <TierBadge tier={asTier(claim.tier)} />
      </div>
      <ul className="verdict-list">
        {claim.verdicts.map(verdict => (
          <VerdictCard key={verdict.id} verdict={verdict} claim={claim} jobId={jobId} applicationId={applicationId} revealed={revealed} />
        ))}
      </ul>
    </li>
  )
}

function VerdictCard({ verdict, claim, jobId, applicationId, revealed }: {
  verdict: StaffVerdict
  claim: StaffClaim
  jobId: number
  applicationId: number
  revealed: boolean
}) {
  const { can } = useAuth()
  const [decision, setDecision] = useState<ReviewDecision | null>(null)
  const review = verdict.myOrgReview
  return (
    <li className="verdict-card">
      <div className="verdict-head">
        <VerdictChip verdict={verdict.verdict} />
        <OwnershipIndicator proven={verdict.ownershipProven} />
        <span className="item-meta">{sourceKindLabel(verdict.sourceKind)} · {verdict.verifier} · {formatDateTime(verdict.createdAt)}</span>
      </div>
      <VerdictBody
        reasons={verdict.reasons}
        integrityNotes={verdict.integrityNotes}
        probeQuestion={verdict.probeQuestion}
        evidence={verdict.evidence}
        evidenceCount={verdict.evidenceCount}
        hiddenEvidenceText={revealed ? 'The sources are not available.' : 'Reveal the candidate to see sources.'}
      />
      {verdict.candidateNote && (
        <div className="verdict-block">
          <h5>Candidate’s note</h5>
          <p className="description-text">{verdict.candidateNote}</p>
        </div>
      )}
      <div className="verdict-review" aria-label="Your organisation’s review">
        {review ? (
          <p>
            <strong>{DECISION_LABELS[review.decision] ?? humanize(review.decision)}</strong> by {review.reviewerName ?? 'a former team member'} on {formatDateTime(review.at)}
            {review.note && <><br /><span className="description-text">“{review.note}”</span></>}
          </p>
        ) : (
          <p className="muted-copy">Not reviewed by your organisation yet. Only a confirmed, verified result with proven ownership raises the evidence tier.</p>
        )}
        {can('verification.review') && (
          <div className="item-actions">
            <button type="button" className="outline-button" onClick={() => setDecision('CONFIRM')}>{review ? 'Review again: confirm' : 'Confirm'}</button>
            <button type="button" className="outline-button" onClick={() => setDecision('OVERRULE')}>{review ? 'Review again: overrule' : 'Overrule'}</button>
          </div>
        )}
      </div>
      <ReviewDialog
        open={decision != null}
        initialDecision={decision ?? 'CONFIRM'}
        verdict={verdict}
        claimLabel={`${humanize(claim.claimType)}: ${claim.value}`}
        jobId={jobId}
        applicationId={applicationId}
        onClose={() => setDecision(null)}
      />
    </li>
  )
}

/**
 * Confirm / Overrule with a note (required for Overrule; the backend answers 400 otherwise). After a review the backend
 * re-matches the candidate in this organisation, so the applicant detail and lists are refreshed too.
 */
export function ReviewDialog({ open, initialDecision, verdict, claimLabel, jobId, applicationId, onClose }: {
  open: boolean
  initialDecision: ReviewDecision
  verdict: StaffVerdict
  claimLabel: string
  jobId: number
  applicationId: number
  onClose: () => void
}) {
  const queryClient = useQueryClient()
  const notices = useNotices()
  const baseId = useId()
  const noteRef = useRef<HTMLTextAreaElement>(null)
  const [decision, setDecision] = useState<ReviewDecision>(initialDecision)
  const [note, setNote] = useState('')
  const [problem, setProblem] = useState<string | null>(null)

  useEffect(() => {
    if (open) {
      setDecision(initialDecision)
      setNote('')
      setProblem(null)
    }
  }, [open, initialDecision])

  const save = useMutation({
    mutationFn: () => verificationApi.review(verdict.id, { decision, note: note.trim() || undefined }),
    onSuccess: () => {
      notices.success(decision === 'CONFIRM'
        ? 'Result confirmed. Matching is recalculated for this candidate.'
        : 'Result overruled. Matching is recalculated for this candidate.')
      void queryClient.invalidateQueries({ queryKey: qk.applicantVerification(applicationId) })
      void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, applicationId) })
      void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
      onClose()
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    const found = reviewProblem(decision, note)
    setProblem(found)
    if (found) {
      noteRef.current?.focus()
      return
    }
    save.mutate()
  }

  const noteRequired = decision === 'OVERRULE'

  return (
    <Dialog
      open={open}
      title="Review this verification result"
      description={<>
        <strong>{claimLabel}</strong>. The agent’s result: {verdictInfo(verdict.verdict).label}. Confirming a verified result with
        proven ownership raises the claim to Corroborated for your organisation only; overruling keeps it where it was.
      </>}
      onClose={onClose}
      busy={save.isPending}
    >
      <form className="dialog-form" onSubmit={submit} noValidate>
        <fieldset className="decision-picker">
          <legend>Your decision</legend>
          <label className={`recommendation-option${decision === 'CONFIRM' ? ' selected rec-yes' : ''}`}>
            <input type="radio" name={`${baseId}-decision`} value="CONFIRM" checked={decision === 'CONFIRM'} onChange={() => { setDecision('CONFIRM'); setProblem(null) }} data-autofocus={initialDecision === 'CONFIRM' || undefined} />
            Confirm: the result matches what I see
          </label>
          <label className={`recommendation-option${decision === 'OVERRULE' ? ' selected rec-no' : ''}`}>
            <input type="radio" name={`${baseId}-decision`} value="OVERRULE" checked={decision === 'OVERRULE'} onChange={() => { setDecision('OVERRULE'); setProblem(null) }} data-autofocus={initialDecision === 'OVERRULE' || undefined} />
            Overrule: I disagree with the result
          </label>
        </fieldset>
        <div className="field">
          <label htmlFor={`${baseId}-note`}>
            Note {noteRequired ? <span className="required-mark">* (required to overrule)</span> : <span className="optional-mark">(optional)</span>}
          </label>
          <textarea
            ref={noteRef}
            id={`${baseId}-note`}
            rows={4}
            maxLength={1000}
            value={note}
            onChange={event => setNote(event.target.value)}
            required={noteRequired}
            aria-invalid={Boolean(problem)}
            aria-describedby={problem ? `${baseId}-note-error` : undefined}
            placeholder={noteRequired ? 'Why do you disagree? This is kept with the review.' : 'Optional context for your team'}
          />
          {problem && <small className="field-error" id={`${baseId}-note-error`} role="alert">{problem}</small>}
        </div>
        <FormError error={save.error} />
        <div className="dialog-actions">
          <button type="button" className="outline-button" onClick={onClose} disabled={save.isPending}>Cancel</button>
          <button type="submit" className="primary-button workspace-primary" disabled={save.isPending}>
            {save.isPending ? 'Saving…' : decision === 'CONFIRM' ? 'Confirm result' : 'Overrule result'}
          </button>
        </div>
      </form>
    </Dialog>
  )
}
