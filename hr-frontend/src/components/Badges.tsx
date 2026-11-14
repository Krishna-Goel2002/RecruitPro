import type { EvidenceTier } from '../api/types'
import { flagInfo, humanize, TIER_DESCRIPTIONS, TIER_LABELS } from '../lib/format'

/** Job, application, work-sample, interview and invitation statuses share one pill style. */
export function StatusBadge({ status, label }: { status: string; label?: string }) {
  return <span className={`status-badge status-${status.toLowerCase()}`}><i aria-hidden="true" />{label ?? humanize(status)}</span>
}

/** Evidence-ladder tier. `null` means no evidence at any tier. */
export function TierBadge({ tier }: { tier: EvidenceTier | null }) {
  if (!tier) return <span className="tier-badge tier-none" title="No supporting evidence was found.">No evidence</span>
  return (
    <span className={`tier-badge tier-${tier.toLowerCase()}`} title={TIER_DESCRIPTIONS[tier]}>
      <span className="tier-steps" aria-hidden="true">{[0, 1, 2, 3, 4].map(step => <i key={step} className={step <= tierIndex(tier) ? 'on' : ''} />)}</span>
      {TIER_LABELS[tier]}
    </span>
  )
}

export function tierIndex(tier: EvidenceTier): number {
  return ['SELF_CLAIMED', 'RESUME_STATED', 'CORROBORATED', 'DEMONSTRATED', 'INTERVIEW_VERIFIED'].indexOf(tier)
}

export function FlagChips({ flags }: { flags: string[] }) {
  if (!flags.length) return null
  return (
    <ul className="flag-chips" aria-label="Resume flags">
      {flags.map(flag => {
        const info = flagInfo(flag)
        return <li key={flag} className="flag-chip" title={info.description}><span aria-hidden="true">⚑</span> {info.label}<span className="sr-only">: {info.description}</span></li>
      })}
    </ul>
  )
}

export function RequirementTags({ mandatory, knockout, minimumYears }: { mandatory: boolean; knockout: boolean; minimumYears: number | null }) {
  return (
    <span className="requirement-tags">
      {mandatory ? <span className="req-tag must">Must-have</span> : <span className="req-tag nice">Nice-to-have</span>}
      {knockout && <span className="req-tag knockout" title="Applicants are ranked only after this must-have is verified.">Ranking gate</span>}
      {minimumYears != null && <span className="req-tag">{minimumYears}+ yrs</span>}
    </span>
  )
}
