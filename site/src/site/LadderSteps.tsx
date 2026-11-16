import { TIER_DESCRIPTION, TIERS, tierLabel, tierOrdinal, type Audience, type Tier } from '../design/lib/labels'
import { cx } from '../design/components'
import { useSeen } from './reveal'

export interface LadderStepsProps {
  /** Current tier (fill level). null = no evidence yet. */
  tier: Tier | null
  audience?: Audience
  /** Show the one-line description under each label (the "How it works" legend). */
  describe?: boolean
  /** Hide from assistive tech when a caption next to it already says the same thing. */
  decorative?: boolean
  className?: string
}

/**
 * The evidence ladder drawn large for the landing page: five rising bars, filled bottom-up to the tier. Same encoding
 * as the product's EvidenceLadder (fill level = tier, Self-claimed = dashed hollow), just bigger. The bars grow in on
 * first view with a 90 ms stagger; a later tier change fills only the new bar (settle ease). Reduced motion: static.
 */
export function LadderSteps({ tier, audience = 'staff', describe, decorative, className }: LadderStepsProps) {
  const { ref, seen } = useSeen<HTMLDivElement>({ threshold: 0.3 })
  const ordinal = tierOrdinal(tier)
  const claimed = tier === 'SELF_CLAIMED'
  const label = tier ? `${tierLabel(tier, audience)} (tier ${ordinal} of 5)` : tierLabel(null, audience)
  return (
    <div
      ref={ref}
      className={cx('lad', className)}
      
      data-describe={describe || undefined}
      role={decorative ? undefined : 'img'}
      aria-hidden={decorative || undefined}
      aria-label={decorative ? undefined : `Evidence ladder: ${label}`}
    >
      {TIERS.map((t, i) => {
        const filled = seen && (i < ordinal) && !(claimed && i === 0)
        const hollow = seen && claimed && i === 0
        return (
          <div key={t} className="lad-step" style={{ ['--i' as string]: i }} data-current={i + 1 === ordinal || undefined}>
            <div className="lad-bay">
              <div className="lad-track">
                <span className="lad-fill" data-filled={filled || undefined} data-hollow={hollow || undefined} />
              </div>
            </div>
            <span className="lad-label">{tierLabel(t, audience)}</span>
            {describe && <span className="lad-desc">{TIER_DESCRIPTION[t]}</span>}
          </div>
        )
      })}
    </div>
  )
}
