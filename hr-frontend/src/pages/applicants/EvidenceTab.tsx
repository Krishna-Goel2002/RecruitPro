import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type { ApplicantDetail, CandidateClaim, RequirementEvidence } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { InlineError } from '../../components/QueryError'
import {
  Button, CoverageMeter, EmptyState, EvidenceLadder, Popover, ProvenanceLine, Quote, ReqMarker, Spinner, Textarea, useToast,
} from '../../design/components'
import {
  CalendarClock, ChevronDown, CircleAlert, FileText, GateOpen, Icon, Info, ListChecks, RefreshCw, ShieldCheck, User, UserRoundCheck,
  type IconComponent,
} from '../../design/icons'
import { plural } from '../../design/lib/format'
import { tierLabel } from '../../design/lib/labels'
import { flagInfo, humanize } from '../../lib/format'
import { claimStatus, isGateOpen, orderRequirements, PROVENANCE_WORD, relatedClaim, STATUS_WORD } from './model'

const PROVENANCE_ICON: Record<string, IconComponent> = {
  RESUME: FileText,
  CANDIDATE_CLAIM: User,
  CORROBORATED: UserRoundCheck,
  WORK_SAMPLE: ListChecks,
  INTERVIEW: CalendarClock,
}

const evidenced = (r: RequirementEvidence) => r.status === 'MATCHED' && r.tier != null && r.tier !== 'SELF_CLAIMED'

// ================================================================================================ glance strip

/**
 * Evidence at a glance: coverage (counts up once), one ladder cell per requirement (must-haves first, open gates
 * outlined) that jumps to its row, and where the applicant stands in the ranking.
 */
export function GlanceStrip({ detail, polling, pollTimedOut, onCheckAgain, canRetry, retrying, onRetry, onOpenRequirement }: {
  detail: ApplicantDetail
  polling: boolean
  pollTimedOut: boolean
  onCheckAgain: () => void
  canRetry: boolean
  retrying: boolean
  onRetry: () => void
  onOpenRequirement: (requirementId: number) => void
}) {
  const [flagsAnchor, setFlagsAnchor] = useState<HTMLButtonElement | null>(null)
  const [flagsOpen, setFlagsOpen] = useState(false)
  const must = detail.requirements.filter(r => r.mandatory)
  const nice = detail.requirements.filter(r => !r.mandatory)
  const openGates = detail.requirements.filter(r => isGateOpen(r, detail))
  const done = detail.requirements.filter(evidenced).length
  const analysisFailed = detail.analysisStatus === 'FAILED' || detail.matchStatus === 'FAILED'

  const cell = (r: RequirementEvidence) => {
    const gate = isGateOpen(r, detail)
    const label = `${r.description}: ${tierLabel(r.tier)}${gate ? ', ranking gate open' : ''}. Show details`
    return (
      <button key={r.requirementId} type="button" className="ap-strip-cell" aria-label={label} title={`${r.description} · ${tierLabel(r.tier)}${gate ? ' · gate open' : ''}`} onClick={() => onOpenRequirement(r.requirementId)}>
        <EvidenceLadder variant="cell" tier={r.tier} gateOpen={gate} />
      </button>
    )
  }

  return (
    <section className="ap-glance" aria-label="Evidence at a glance">
      <div className="ap-glance-coverage">
        {detail.matchStatus === 'READY' && detail.verifiedCoverage != null ? (
          <CoverageMeter verified={detail.verifiedCoverage} claimed={detail.claimedCoverage ?? detail.verifiedCoverage} />
        ) : detail.matchStatus === 'PENDING' ? (
          <p className="ap-glance-status" role="status">
            {pollTimedOut ? <Icon icon={Info} size={16} /> : <Spinner size={14} />}
            <span>{pollTimedOut ? 'Matching is taking longer than usual.' : polling ? 'Matching in progress. This page updates by itself.' : 'Matching in progress.'}</span>
            {pollTimedOut && <Button size="sm" variant="secondary" icon={RefreshCw} onClick={onCheckAgain}>Check again</Button>}
          </p>
        ) : (
          <p className="ap-glance-status">
            <Icon icon={analysisFailed ? CircleAlert : Info} size={16} />
            <span>
              {detail.matchStatus === 'NO_REQUIREMENTS' ? 'The job has no requirements, so nothing was matched.'
                : detail.matchStatus === 'ANALYSIS_UNAVAILABLE' ? 'The resume could not be read yet, and there is no other evidence.'
                  : 'Matching did not finish.'}
            </span>
            {analysisFailed && canRetry && <Button size="sm" variant="secondary" icon={RefreshCw} loading={retrying} onClick={onRetry}>Retry analysis</Button>}
          </p>
        )}
      </div>

      {detail.requirements.length > 0 && (
        <div className="ap-glance-strip">
          <p className="ap-glance-label">
            <span className="numeric">{done}</span> of <span className="numeric">{detail.requirements.length}</span> requirements evidenced
          </p>
          <div className="ap-strip" role="group" aria-label="Requirements by evidence (must-haves first)">
            {must.map(cell)}
            {must.length > 0 && nice.length > 0 && <span className="ap-strip-gap" aria-hidden="true" />}
            {nice.map(cell)}
          </div>
          <p className="ap-glance-legend" aria-hidden="true"><span>Must-haves</span>{nice.length > 0 && <span>Nice to have</span>}</p>
        </div>
      )}

      <div className="ap-glance-group">
        {detail.rankGroup === 'RANKED' ? (
          <p><Icon icon={ShieldCheck} size={16} /><span><strong>Ranked{detail.rank != null ? ` #${detail.rank}` : ''}</strong> · every ranking gate is verified</span></p>
        ) : detail.rankGroup === 'MUST_HAVES_UNVERIFIED' ? (
          <p data-tone="warning">
            <Icon icon={GateOpen} size={16} />
            <span>
              <strong>{openGates.length === 1 ? 'Gate open' : `${openGates.length || 'Some'} gates open`}</strong>
              {openGates.length > 0 && <>: {openGates.map(r => r.description).join(', ')}</>}
              <span className="ap-glance-sub">Held until evidenced. Not a rejection.</span>
            </span>
          </p>
        ) : (
          <p><Icon icon={Info} size={16} /><span><strong>Unscored</strong> · no match result yet</span></p>
        )}
        {detail.flags?.length > 0 && (
          <>
            <button type="button" ref={setFlagsAnchor} className="ap-flags-btn" aria-expanded={flagsOpen} onClick={() => setFlagsOpen(o => !o)}>
              <Icon icon={FileText} size={14} />
              {plural(detail.flags.length, 'note')} about the resume file
            </button>
            <Popover anchor={flagsAnchor} open={flagsOpen} onClose={() => setFlagsOpen(false)} label="Notes about the resume file" align="start" className="ap-flags-pop">
              <p className="ap-pop-title">Notes about the resume file</p>
              <ul>
                {detail.flags.map(flag => <li key={flag}><strong>{flagInfo(flag).label}.</strong> {flagInfo(flag).description}</li>)}
              </ul>
              <p className="ap-pop-foot">Notes describe the file, not the candidate.</p>
            </Popover>
          </>
        )}
      </div>
    </section>
  )
}

// ================================================================================================ evidence tab

export function EvidenceTab({ jobId, detail }: { jobId: number; detail: ApplicantDetail }) {
  const ordered = orderRequirements(detail.requirements, detail)
  const [open, setOpen] = useState<Set<number>>(() => new Set(ordered.filter(r => isGateOpen(r, detail)).map(r => r.requirementId)))
  const [legendAnchor, setLegendAnchor] = useState<HTMLButtonElement | null>(null)
  const [legendOpen, setLegendOpen] = useState(false)
  const toggle = (id: number, force?: boolean) =>
    setOpen(current => {
      const next = new Set(current)
      const want = force ?? !next.has(id)
      if (want) next.add(id)
      else next.delete(id)
      return next
    })
  const allOpen = ordered.length > 0 && ordered.every(r => open.has(r.requirementId))

  if (detail.requirements.length === 0) {
    return (
      <EmptyState
        icon={ListChecks}
        title={detail.matchStatus === 'PENDING' ? 'Matching in progress' : 'No requirement results yet'}
        description={detail.matchStatus === 'NO_REQUIREMENTS' ? 'Add requirements to the job to rank applicants on evidence.' : 'Results appear here when matching finishes.'}
      />
    )
  }

  return (
    <div className="ap-evidence">
      <div className="ap-tab-head">
        <p className="ap-tab-intro">
          Open gates first, then must-haves. <strong>Verified</strong> coverage counts resume-level evidence and stronger; claims count only towards <strong>With claims</strong>.
        </p>
        <div className="ap-tab-actions">
          <Button size="sm" variant="ghost" ref={setLegendAnchor} aria-expanded={legendOpen} onClick={() => setLegendOpen(o => !o)}>How the ladder works</Button>
          <Button size="sm" variant="ghost" onClick={() => setOpen(allOpen ? new Set() : new Set(ordered.map(r => r.requirementId)))}>
            {allOpen ? 'Collapse all' : 'Expand all'}
          </Button>
        </div>
        <Popover anchor={legendAnchor} open={legendOpen} onClose={() => setLegendOpen(false)} label="How the evidence ladder works" className="ap-legend-pop">
          <p className="ap-pop-title">How the evidence ladder works</p>
          <EvidenceLadder variant="legend" tier={null} audience="staff" />
          <p className="ap-pop-foot">Each rung is stronger evidence. A missing must-have never rejects anyone automatically; a person decides.</p>
        </Popover>
      </div>

      <ol className="ap-reqs">
        {ordered.map(req => (
          <RequirementRow
            key={req.requirementId}
            jobId={jobId}
            detail={detail}
            req={req}
            open={open.has(req.requirementId)}
            onToggle={force => toggle(req.requirementId, force)}
          />
        ))}
      </ol>
      <p className="ap-algo">
        {detail.algorithmVersion ? <>Matched by <span className="mono">{detail.algorithmVersion}</span>{detail.matchRunId ? <> · run #{detail.matchRunId}</> : null}{detail.calculatedAt ? <> · {new Date(detail.calculatedAt).toLocaleString('en-IN', { dateStyle: 'medium', timeStyle: 'short' })}</> : null}</> : 'Not matched yet'}
        {detail.analysisStatus && <> · resume analysis {humanize(detail.analysisStatus).toLowerCase()}</>}
      </p>
    </div>
  )
}

function RequirementRow({ jobId, detail, req, open, onToggle }: {
  jobId: number
  detail: ApplicantDetail
  req: RequirementEvidence
  open: boolean
  onToggle: (force?: boolean) => void
}) {
  const rowRef = useRef<HTMLLIElement>(null)
  const bodyId = useId()
  const gate = isGateOpen(req, detail)
  const claim = relatedClaim(req, detail.claims)
  const provenance = req.provenance ?? null

  useEffect(() => {
    const el = rowRef.current
    if (!el) return
    const onOpen = () => onToggle(true)
    el.addEventListener('ap:open', onOpen)
    return () => el.removeEventListener('ap:open', onOpen)
  }, [onToggle])

  return (
    <li ref={rowRef} id={`req-${req.requirementId}`} className="ap-req" data-open={open || undefined} data-gate={gate || undefined} data-status={req.status}>
      <div className="ap-req-head">
        <div className="ap-req-name">
          <span className="ap-req-markers">
            <ReqMarker kind={req.mandatory ? 'must' : 'nice'} />
            {req.knockout && <ReqMarker kind="gate">{gate ? 'Gate open' : 'Gate'}</ReqMarker>}
          </span>
          <button type="button" className="ap-req-toggle" aria-expanded={open} aria-controls={bodyId} onClick={() => onToggle()}>
            {req.description}
          </button>
          <span className="ap-req-meta">
            {humanize(req.type)}
            {req.minimumYears ? <> · {req.minimumYears}+ years</> : null}
            {req.alternatives?.length ? <> · also accepts {req.alternatives.join(', ')}</> : null}
          </span>
        </div>
        <div className="ap-req-tier">
          <EvidenceLadder tier={req.tier} audience="staff" gateOpen={gate} />
          <span className="ap-req-status" data-status={req.status}>{STATUS_WORD[req.status]}</span>
        </div>
        <div className="ap-req-peek" aria-hidden={open || undefined}>
          {req.evidence ? <span className="ap-req-quote">“{req.evidence}”</span> : <span className="ap-req-explain">{req.explanation}</span>}
        </div>
        <Icon icon={ChevronDown} size={16} className="ap-req-chevron" />
      </div>
      <div className="ap-req-body" id={bodyId} aria-hidden={!open || undefined} {...(open ? {} : ({ inert: '' } as Record<string, string>))}>
        <div><div className="ap-req-body-inner">
          <div className="ap-req-why">
            <h4 className="t-label">Why</h4>
            <p>{req.explanation}</p>
            {req.verificationHint && (
              <p className="ap-req-hint"><strong>To verify:</strong> {req.verificationHint}</p>
            )}
          </div>
          <div className="ap-req-proof">
            <h4 className="t-label">Evidence</h4>
            {req.evidence ? (
              <>
                <Quote>{req.evidence}</Quote>
                <ProvenanceLine
                  icon={provenance ? PROVENANCE_ICON[provenance] : FileText}
                  source={provenance ? PROVENANCE_WORD[provenance] ?? humanize(provenance) : 'Resume text'}
                  actor={req.sourceSection ? humanize(req.sourceSection) : undefined}
                />
              </>
            ) : (
              <p className="ap-muted">{req.status === 'NOT_OBSERVED' ? 'Nothing found in the resume or claims. This does not mean the candidate lacks it.' : 'No quote for this result.'}</p>
            )}
            {claim && <RelatedClaim jobId={jobId} detail={detail} claim={claim} />}
          </div>
        </div></div>
      </div>
    </li>
  )
}

function RelatedClaim({ jobId, detail, claim }: { jobId: number; detail: ApplicantDetail; claim: CandidateClaim }) {
  const { can } = useAuth()
  const status = claimStatus(claim)
  return (
    <div className="ap-related">
      <p className="ap-related-line">
        <span className="ap-related-label">Related claim</span>
        <strong>{claim.value}</strong>
        <EvidenceLadder tier={claim.tier} audience="staff" animate={false} />
        {status !== 'UNVERIFIED' && <span className="ap-related-state" data-state={status}>{status === 'VERIFIED' ? 'Verified by a reviewer' : 'Marked not verified'}</span>}
      </p>
      {can('applicants.verifyClaims') && status === 'UNVERIFIED' && <ClaimVerifyActions jobId={jobId} detail={detail} claim={claim} />}
    </div>
  )
}

/**
 * Verify / Not verified with a note in a popover (Enter submits, Shift+Enter for a new line). The server corroborates
 * the claim and re-matches; the ladder lifts in place when the new result arrives.
 */
export function ClaimVerifyActions({ jobId, detail, claim, size = 'sm' }: { jobId: number; detail: ApplicantDetail; claim: CandidateClaim; size?: 'sm' | 'md' }) {
  const queryClient = useQueryClient()
  const { toast } = useToast()
  const [anchor, setAnchor] = useState<HTMLButtonElement | null>(null)
  const [verifyBtn, setVerifyBtn] = useState<HTMLButtonElement | null>(null)
  const [rejectBtn, setRejectBtn] = useState<HTMLButtonElement | null>(null)
  const [mode, setMode] = useState<boolean | null>(null)
  const [note, setNote] = useState('')
  const status = claimStatus(claim)

  const verify = useMutation({
    mutationFn: (verified: boolean) => reviewApi.verifyClaim(jobId, detail.applicationId, claim.id, { verified, note: note.trim() || undefined }),
    onSuccess: (_c, verified) => {
      setMode(null)
      setNote('')
      toast({
        tone: 'success',
        title: verified ? 'Claim corroborated' : 'Marked not verified',
        body: verified ? 'Matching re-runs with the stronger evidence; the ladder updates here.' : 'It stays at Claimed and does not count as verified.',
      })
      void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, detail.applicationId) })
      void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
    },
  })

  function openFor(verified: boolean, el: HTMLButtonElement | null) {
    verify.reset()
    setAnchor(el)
    setMode(verified)
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (mode != null) verify.mutate(mode)
  }

  return (
    <div className="ap-claim-actions">
      <Button ref={setVerifyBtn} size={size} variant="secondary" icon={UserRoundCheck} disabled={status === 'VERIFIED'} aria-expanded={mode === true} onClick={() => openFor(true, verifyBtn)}>
        Verify claim
      </Button>
      <Button ref={setRejectBtn} size={size} variant="ghost" disabled={status === 'REJECTED'} aria-expanded={mode === false} onClick={() => openFor(false, rejectBtn)}>
        Not verified
      </Button>
      <Popover anchor={anchor} open={mode != null} onClose={() => setMode(null)} label={mode ? 'Verify this claim' : 'Mark this claim as not verified'} align="start" className="ap-verify-pop">
        <form onSubmit={submit} className="ap-verify-form">
          <p className="ap-pop-title">{mode ? 'Verify' : 'Not verified'}: {claim.value}</p>
          <p className="ap-pop-sub">
            {mode ? 'Raises it to Corroborated for your organisation, recorded with your name in the audit log.' : 'It stays at Claimed and does not count as verified evidence.'}
          </p>
          <label className="t-label" htmlFor={`${claim.id}-note`}>Note <span className="ap-muted">(optional)</span></label>
          <Textarea
            id={`${claim.id}-note`}
            rows={2}
            maxRows={5}
            maxLength={500}
            autoFocus
            value={note}
            placeholder={mode ? 'What did you check? e.g. badge ID matches the issuer record' : 'Why it could not be verified'}
            onChange={event => setNote(event.target.value)}
            onKeyDown={event => {
              if (event.key === 'Enter' && !event.shiftKey) {
                event.preventDefault()
                event.currentTarget.form?.requestSubmit()
              }
            }}
          />
          <InlineError error={verify.error} />
          <div className="ap-verify-actions">
            <Button size="sm" variant="ghost" onClick={() => setMode(null)} disabled={verify.isPending}>Cancel</Button>
            <Button size="sm" type="submit" variant={mode ? 'primary' : 'secondary'} loading={verify.isPending}>
              {mode ? 'Verify claim' : 'Mark not verified'}
            </Button>
          </div>
        </form>
      </Popover>
    </div>
  )
}
