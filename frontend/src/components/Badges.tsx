import type { EvidenceTier, RequirementVerification } from '../api/types'
import { TIER_DESCRIPTION, TIER_LABEL, TIER_ORDER, VERIFICATION_LABEL, applicationStatusLabel, humanize } from '../lib/labels'

export function StatusBadge({ status }: { status: string }) {
  return <span className={`status status-${status.toLowerCase()}`}>{applicationStatusLabel(status)}</span>
}

/** Evidence-ladder tier with its plain-language meaning as a tooltip and for screen readers. */
export function TierBadge({ tier }: { tier: EvidenceTier | string | null | undefined }) {
  if (!tier) return null
  const known = (TIER_ORDER as readonly string[]).includes(tier)
  const level = known ? TIER_ORDER.indexOf(tier as EvidenceTier) : 0
  const label = known ? TIER_LABEL[tier as EvidenceTier] : humanize(tier)
  const description = known ? TIER_DESCRIPTION[tier as EvidenceTier] : undefined
  return (
    <span className={`tier tier-${level}`} title={description}>
      {label}
      {description && <span className="visually-hidden">: {description}</span>}
    </span>
  )
}

export function VerificationBadge({ verification }: { verification: RequirementVerification }) {
  return <span className={`verification verification-${verification.toLowerCase()}`}>{VERIFICATION_LABEL[verification]}</span>
}

export function MustHaveBadge({ mandatory }: { mandatory: boolean }) {
  return mandatory ? <span className="badge badge-must">Must-have</span> : <span className="badge badge-nice">Nice to have</span>
}
