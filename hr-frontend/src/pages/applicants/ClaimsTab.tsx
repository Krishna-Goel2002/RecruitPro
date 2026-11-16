import type { ApplicantDetail } from '../../api/types'
import { useAuth } from '../../auth/AuthContext'
import { EmptyState, EvidenceLadder, ProvenanceLine } from '../../design/components'
import { ArrowUpRight, EyeOff, Icon, User, UserRoundCheck } from '../../design/icons'
import { relativeTime } from '../../design/lib/format'
import { humanize, safeHttpUrl } from '../../lib/format'
import { ClaimVerifyActions } from './EvidenceTab'
import { claimStatus } from './model'

/**
 * Things the candidate added or corrected themselves. They stay "Claimed" until a reviewer checks the linked evidence;
 * verifying lifts the claim to Corroborated for this organisation only.
 */
export function ClaimsTab({ jobId, detail }: { jobId: number; detail: ApplicantDetail }) {
  const { can } = useAuth()
  if (detail.claims.length === 0) {
    return <EmptyState icon={User} title="No claims added" description="The candidate has not added or corrected anything beyond the resume." />
  }
  const open = detail.claims.filter(c => claimStatus(c) === 'UNVERIFIED').length
  return (
    <div className="ap-claims">
      <p className="ap-tab-intro">
        Added or corrected by the candidate. {open > 0 ? <><strong className="numeric">{open}</strong> still {open === 1 ? 'needs' : 'need'} checking.</> : 'All have been checked.'}
      </p>
      <ul className="ap-claim-list">
        {detail.claims.map(claim => {
          // Blind-first: links can identify the candidate, so they are shown only after reveal (the server also withholds them).
          const url = detail.identityRevealed ? safeHttpUrl(claim.evidenceUrl) : null
          const status = claimStatus(claim)
          return (
            <li key={claim.id} className="ap-claim" data-status={status}>
              <div className="ap-claim-main">
                <span className="ap-claim-type">{humanize(claim.claimType)}</span>
                <span className="ap-claim-value">{claim.value}</span>
                <span className="ap-claim-link">
                  {url ? (
                    <a href={url} target="_blank" rel="noopener noreferrer nofollow">
                      Evidence link <Icon icon={ArrowUpRight} size={12} /><span className="sr-only"> (opens in a new tab)</span>
                    </a>
                  ) : claim.hasEvidenceUrl || (!detail.identityRevealed && claim.evidenceUrl) ? (
                    <span className="ap-muted"><Icon icon={EyeOff} size={12} /> Link hidden until identity is revealed</span>
                  ) : (
                    <span className="ap-muted">No evidence link</span>
                  )}
                  <span className="ap-muted"> · added {relativeTime(claim.createdAt)}</span>
                </span>
              </div>
              <div className="ap-claim-tier">
                <EvidenceLadder tier={claim.tier} audience="staff" />
              </div>
              <div className="ap-claim-review">
                {status === 'UNVERIFIED' ? (
                  can('applicants.verifyClaims') ? <ClaimVerifyActions jobId={jobId} detail={detail} claim={claim} /> : <span className="ap-muted">Not checked yet</span>
                ) : (
                  <div className="ap-claim-checked">
                    <ProvenanceLine
                      icon={UserRoundCheck}
                      source={status === 'VERIFIED' ? 'Verified by a reviewer' : 'Marked not verified'}
                      at={claim.verifiedAt}
                    />
                    {claim.verificationNote && <p className="ap-claim-note">“{claim.verificationNote}”</p>}
                    {can('applicants.verifyClaims') && (
                      <details className="ap-claim-change">
                        <summary>Change</summary>
                        <ClaimVerifyActions jobId={jobId} detail={detail} claim={claim} />
                      </details>
                    )}
                  </div>
                )}
              </div>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
