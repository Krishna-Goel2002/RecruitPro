import { useState } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import { ApplicantDetail, CandidateClaim, EVIDENCE_TIERS } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { FlagChips, RequirementTags, TierBadge } from '../../components/Badges'
import { CoverageBars } from '../../components/CoverageBars'
import { TextPromptDialog } from '../../components/Dialog'
import { useNotices } from '../../components/Notices'
import { flagInfo, formatDateTime, humanize, safeHttpUrl, TIER_DESCRIPTIONS, TIER_LABELS } from '../../lib/format'

const GROUP_TEXT = {
  RANKED: 'Ranked: every ranking-gate requirement is verified.',
  MUST_HAVES_UNVERIFIED: 'Must-haves not yet verified: at least one ranking gate still needs evidence. Not a rejection.',
  UNSCORED: 'Unscored: no match result yet.',
} as const

const STATUS_TEXT = { MATCHED: 'Matched', UNCLEAR: 'Unclear', NOT_OBSERVED: 'Not observed' } as const

const PROVENANCE_TEXT: Record<string, string> = {
  RESUME: 'resume text',
  CANDIDATE_CLAIM: 'the candidate’s own claim',
  CORROBORATED: 'claim checked by a recruiter',
  WORK_SAMPLE: 'scored work sample',
  INTERVIEW: 'structured interview',
}

/** Coverage summary and the evidence-ladder table (C3). */
export function EvidenceSection({ detail }: { detail: ApplicantDetail }) {
  return (
    <section className="detail-card section-card" aria-labelledby="evidence-heading">
      <div className="section-head">
        <div>
          <h2 id="evidence-heading">Requirement evidence</h2>
          <p>Coverage is the share of weighted requirements (must-haves count double) with evidence — not a probability of success.</p>
        </div>
      </div>

      <div className="evidence-summary">
        {detail.matchStatus === 'READY' ? (
          <CoverageBars verified={detail.verifiedCoverage} claimed={detail.claimedCoverage} />
        ) : (
          <p className={`match-note match-${detail.matchStatus.toLowerCase()}`}>
            {detail.matchStatus === 'PENDING' && <span className="spinner dark-spinner small" aria-hidden="true" />}
            {detail.matchStatus === 'PENDING' ? 'Matching in progress — this page updates automatically.'
              : detail.matchStatus === 'NO_REQUIREMENTS' ? 'The job has no requirements, so nothing was matched.'
                : detail.matchStatus === 'ANALYSIS_UNAVAILABLE' ? 'The resume has not been analysed yet (or could not be read).'
                  : 'Matching failed. An HR manager can retry the analysis.'}
          </p>
        )}
        <div className="evidence-facts">
          <span>{GROUP_TEXT[detail.rankGroup]}{detail.rank != null && detail.rankGroup === 'RANKED' ? ` Rank #${detail.rank}.` : ''}</span>
          {detail.algorithmVersion && <span>Algorithm {detail.algorithmVersion} · calculated {formatDateTime(detail.calculatedAt)}</span>}
          {detail.analysisStatus && <span>Resume analysis: {humanize(detail.analysisStatus).toLowerCase()}</span>}
        </div>
        {detail.flags?.length > 0 && (
          <div className="flag-explanations">
            <FlagChips flags={detail.flags} />
            <ul>{detail.flags.map(flag => <li key={flag}><strong>{flagInfo(flag).label}:</strong> {flagInfo(flag).description}</li>)}</ul>
          </div>
        )}
      </div>

      <details className="tier-legend">
        <summary>How the evidence ladder works</summary>
        <ol>
          {EVIDENCE_TIERS.map(tier => <li key={tier}><TierBadge tier={tier} /> {TIER_DESCRIPTIONS[tier]}</li>)}
        </ol>
        <p>“Verified” coverage counts Resume and stronger. Claims count only towards “incl. claims” until a recruiter corroborates them.</p>
      </details>

      {detail.requirements.length === 0 ? (
        <p className="muted-copy">No requirement results yet.</p>
      ) : (
        <div className="job-table-wrap">
          <table className="job-table evidence-table">
            <caption className="sr-only">Evidence for each requirement</caption>
            <thead>
              <tr>
                <th scope="col">REQUIREMENT</th>
                <th scope="col">STATUS</th>
                <th scope="col">TIER</th>
                <th scope="col">EVIDENCE AND EXPLANATION</th>
              </tr>
            </thead>
            <tbody>
              {detail.requirements.map(item => (
                <tr key={item.requirementId} className={`evidence-row status-${item.status.toLowerCase()}${item.knockout && (!item.tier || item.tier === 'SELF_CLAIMED' || item.status !== 'MATCHED') ? ' gate-open' : ''}`}>
                  <th scope="row">
                    <span className="req-type-label">{humanize(item.type)}</span>
                    <span className="req-description">{item.description}</span>
                    {item.alternatives?.length > 0 && <span className="item-meta">or {item.alternatives.join(', ')}</span>}
                    <RequirementTags mandatory={item.mandatory} knockout={item.knockout} minimumYears={item.minimumYears} />
                  </th>
                  <td><span className={`match-status-pill match-${item.status.toLowerCase()}`}>{STATUS_TEXT[item.status]}</span></td>
                  <td><TierBadge tier={item.tier} /></td>
                  <td className="evidence-cell">
                    {item.evidence && <blockquote className="evidence-quote">{item.evidence}{item.sourceSection && <cite> — {humanize(item.sourceSection)}</cite>}</blockquote>}
                    <p className="evidence-explanation">{item.explanation}</p>
                    {item.provenance && <p className="item-meta">Source: {PROVENANCE_TEXT[item.provenance] ?? humanize(item.provenance)}</p>}
                    {item.verificationHint && <p className="verification-hint"><strong>To verify:</strong> {item.verificationHint}</p>}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

/** Candidate claims (tier 0) with Verify / Not verified actions for recruiters and managers. */
export function ClaimsSection({ jobId, detail }: { jobId: number; detail: ApplicantDetail }) {
  const { can } = useAuth()
  const queryClient = useQueryClient()
  const notices = useNotices()
  const [target, setTarget] = useState<{ claim: CandidateClaim; verified: boolean } | null>(null)

  const verify = useMutation({
    mutationFn: ({ claim, verified, note }: { claim: CandidateClaim; verified: boolean; note: string }) =>
      reviewApi.verifyClaim(jobId, detail.applicationId, claim.id, { verified, note: note || undefined }),
    onSuccess: (_claim, variables) => {
      setTarget(null)
      notices.success(variables.verified ? 'Claim corroborated. Matching re-runs with the stronger evidence.' : 'Claim marked as not verified.')
      void queryClient.invalidateQueries({ queryKey: qk.applicant(jobId, detail.applicationId) })
      void queryClient.invalidateQueries({ queryKey: qk.applicants(jobId) })
    },
  })

  return (
    <section className="detail-card section-card" aria-labelledby="claims-heading">
      <div className="section-head">
        <div>
          <h2 id="claims-heading">Candidate claims</h2>
          <p>Things the candidate added or corrected themselves. They stay “Claimed” until someone checks the linked evidence.</p>
        </div>
      </div>
      {detail.claims.length === 0 ? <p className="muted-copy">The candidate has not added any claims.</p> : (
        <ul className="claim-list">
          {detail.claims.map(claim => {
            const url = safeHttpUrl(claim.evidenceUrl)
            const checked = claim.verifiedAt != null
            const status = claim.verificationStatus ?? (checked ? (claim.tier === 'SELF_CLAIMED' ? 'REJECTED' : 'VERIFIED') : 'UNVERIFIED')
            return (
              <li key={claim.id} className="claim-card">
                <div className="claim-main">
                  <span className="req-type-label">{humanize(claim.claimType)}</span>
                  <strong>{claim.value}</strong>
                  <TierBadge tier={claim.tier} />
                </div>
                <div className="claim-meta">
                  {url ? <a href={url} target="_blank" rel="noopener noreferrer nofollow">Evidence link ↗</a>
                    : claim.evidenceUrl ? <span>Evidence: {claim.evidenceUrl}</span>
                      : claim.hasEvidenceUrl ? <span>Evidence link hidden until identity is revealed</span> : <span>No evidence link</span>}
                  <span>Added {formatDateTime(claim.createdAt)}</span>
                  {status !== 'UNVERIFIED' && <span className={`claim-status claim-${status.toLowerCase()}`}>{status === 'VERIFIED' ? 'Verified' : 'Not verified'}</span>}
                  {checked && <span>Checked {formatDateTime(claim.verifiedAt)}{claim.verificationNote ? ` — “${claim.verificationNote}”` : ''}</span>}
                </div>
                {can('applicants.verifyClaims') && (
                  <div className="item-actions">
                    <button type="button" className="outline-button" onClick={() => { verify.reset(); setTarget({ claim, verified: true }) }} disabled={status === 'VERIFIED'}>Verify</button>
                    <button type="button" className="outline-button danger-outline" onClick={() => { verify.reset(); setTarget({ claim, verified: false }) }} disabled={status === 'REJECTED'}>Not verified</button>
                  </div>
                )}
              </li>
            )
          })}
        </ul>
      )}

      <TextPromptDialog
        open={target != null}
        title={target?.verified ? 'Verify this claim?' : 'Mark this claim as not verified?'}
        message={target && <>
          <strong>{target.claim.value}</strong>. {target.verified
            ? `Verifying raises it to ${TIER_LABELS.CORROBORATED}: you checked supporting evidence. It is recorded with your name in the audit log.`
            : 'The claim stays at tier “Claimed” and does not count as verified evidence.'}
        </>}
        label="Note"
        placeholder={target?.verified ? 'What did you check? e.g. certificate number confirmed with issuer' : 'Why it could not be verified'}
        maxLength={500}
        confirmLabel={target?.verified ? 'Verify claim' : 'Mark not verified'}
        danger={target ? !target.verified : false}
        busy={verify.isPending}
        error={verify.error}
        onCancel={() => setTarget(null)}
        onSubmit={note => { if (target) verify.mutate({ claim: target.claim, verified: target.verified, note }) }}
      />
    </section>
  )
}
