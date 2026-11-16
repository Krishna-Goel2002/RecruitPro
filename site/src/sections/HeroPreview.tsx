import { useEffect, useState } from 'react'
import { Avatar, Badge, CoverageMeter, EvidenceLadder, ReqMarker, VerdictChip } from '../design/components'
import { Check, Icon } from '../design/icons'
import type { Tier } from '../design/lib/labels'
import { reduced } from '../design/motion'
import { LadderSteps } from '../site/LadderSteps'

interface Row {
  req: string
  kind: 'must' | 'nice'
  tier: Tier | null
  source: string
}

/** Example applicant (fictional). Coverage follows the product rule: must-have weight 2, nice-to-have 1. */
const ROWS: Row[] = [
  { req: 'Java 17', kind: 'must', tier: 'RESUME_STATED', source: 'Resume · Experience' },
  { req: 'PostgreSQL', kind: 'must', tier: 'DEMONSTRATED', source: 'Work sample · scored 3 of 4' },
  { req: 'Spring Boot', kind: 'must', tier: 'RESUME_STATED', source: 'Resume · Experience' },
  { req: 'Kubernetes', kind: 'nice', tier: 'SELF_CLAIMED', source: 'Candidate claim' },
  { req: 'System design', kind: 'nice', tier: null, source: 'No evidence yet' },
]

/**
 * Hero product preview, built from the real design-system components with example data. It plays one short story,
 * once: a candidate has shared a verified GitHub result for "Java 17"; a recruiter confirms it and the requirement
 * climbs from Resume to Corroborated (M5 settle). Reduced motion: the end state, no timers.
 * Decorative: the figure caption tells the same story to assistive tech.
 */
export function HeroPreview() {
  const [confirmed, setConfirmed] = useState(() => reduced())
  useEffect(() => {
    if (confirmed) return
    const t = window.setTimeout(() => setConfirmed(true), 2200)
    return () => window.clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  const javaTier: Tier = confirmed ? 'CORROBORATED' : 'RESUME_STATED'

  return (
    <figure className="hero-preview" data-confirmed={confirmed || undefined}>
      <figcaption className="sr-only">
        Example applicant A-3014 for a Backend Engineer role, name hidden. Coverage 75% with verified evidence, 88% including
        the candidate&apos;s own claims. A recruiter confirms a verified GitHub result, and Java 17 rises from Resume to
        Corroborated.
      </figcaption>
      <div className="pv" aria-hidden="true">
        <div className="pv-head">
          <Avatar blind size={32} />
          <div className="pv-who">
            <span className="pv-name">Candidate A-3014</span>
            <span className="pv-meta">Backend Engineer · name hidden until shortlist</span>
          </div>
          <Badge tone="neutral" className="pv-example">Example data</Badge>
        </div>

        <div className="pv-focus">
          <div className="pv-focus-head">
            <span className="pv-focus-req">
              Java 17 <ReqMarker kind="must" />
            </span>
            <span className="pv-focus-src">
              <VerdictChip verdict="VERIFIED" size="sm" />
              <span className="pv-src">github.com · shared by candidate</span>
            </span>
          </div>
          <LadderSteps tier={javaTier} decorative className="pv-ladder" />
          <div className="pv-focus-foot">
            <span className="pv-action" data-done={confirmed || undefined}>
              <span className="pv-action-idle">Confirm verdict</span>
              <span className="pv-action-done">
                <Icon icon={Check} size={14} /> Confirmed by recruiter
              </span>
            </span>
            <span className="pv-focus-note">Counts for this organisation only</span>
          </div>
        </div>

        <CoverageMeter verified={75} claimed={88} note={null} className="pv-coverage" />

        <ul className="pv-rows">
          {ROWS.map((r, i) => (
            <li key={r.req} className="pv-row" style={{ ['--i' as string]: i }}>
              <span className="pv-req">
                {r.req}
                {r.kind === 'must' && <ReqMarker kind="must" />}
              </span>
              <EvidenceLadder tier={r.req === 'Java 17' ? javaTier : r.tier} audience="staff" />
              <span className="pv-row-src">{r.req === 'Java 17' && confirmed ? 'Confirmed verdict · github.com' : r.source}</span>
            </li>
          ))}
        </ul>
      </div>
    </figure>
  )
}
