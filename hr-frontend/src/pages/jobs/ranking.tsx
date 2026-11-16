import { useEffect, useRef, useState } from 'react'
import { useQueries } from '@tanstack/react-query'
import { qk } from '../../api/queryKeys'
import { reviewApi } from '../../api/review'
import type {
  ApplicantDetail, ApplicantSummary, ApplicationStatus, DecisionAction, MatchStatus, RankGroup, Requirement, RequirementEvidence,
} from '../../api/types'
import { CountUp, EvidenceLadder, Tooltip } from '../../design/components'
import { tierLabel } from '../../design/lib/labels'
import { reduced } from '../../design/motion'
import { createLimiter } from '../../lib/limit'

export const GROUP_COPY: Record<RankGroup, { title: string; description: string }> = {
  RANKED: {
    title: 'Ranked',
    description: 'Every gate requirement is verified. Ordered by verified coverage, then claimed coverage, then application date.',
  },
  MUST_HAVES_UNVERIFIED: {
    title: 'Must-haves not yet verified',
    description: 'At least one gate is not verified yet. Not rejected: check the evidence, a claim, a work sample or an interview.',
  },
  UNSCORED: {
    title: 'Unscored',
    description: 'No match result yet: the resume is still being read, could not be read, or the job has no requirements.',
  },
}

export const MATCH_NOTE: Record<MatchStatus, string> = {
  READY: '',
  PENDING: 'Matching…',
  NO_REQUIREMENTS: 'No requirements to match against',
  ANALYSIS_UNAVAILABLE: 'Resume could not be read',
  FAILED: 'Matching failed',
}

export const COVERAGE_NOTE =
  'Share of weighted requirements with evidence (must-haves count double). It is not a probability of success.'

/**
 * Requirements in strip order: must-haves first (gates first among them), then nice-to-haves. OTHER requirements are
 * never scored by the engine (handbook 02 §6.3), so they are left out of the strip rather than drawn as permanently empty.
 */
export function stripRequirements(requirements: Requirement[]): Requirement[] {
  const scored = requirements.filter(r => r.type !== 'OTHER')
  const rank = (r: Requirement) => (r.mandatory ? (r.knockout ? 0 : 1) : 2)
  return scored.map((r, i) => ({ r, i })).sort((a, b) => rank(a.r) - rank(b.r) || a.i - b.i).map(x => x.r)
}

/** Decisions the list may offer for a row (the server's allowedActions win when the detail is cached). */
export function canListDecide(action: 'SHORTLIST' | 'REJECT', status: ApplicationStatus, allowed?: DecisionAction[]): boolean {
  if (allowed) return allowed.includes(action)
  if (action === 'SHORTLIST') return status === 'SUBMITTED'
  return status === 'SUBMITTED' || status === 'SHORTLISTED' || status === 'INTERVIEWING'
}

// ------------------------------------------------------------------------------------------------ details prefetch

const limit = createLimiter(4)

/**
 * API gap: the list has no per-requirement tiers. Fetch each visible applicant's detail in the background (4 at a time,
 * cached a minute, same key as the applicant page so opening one is then instant).
 */
export function useApplicantDetails(jobId: number, rows: ApplicantSummary[]) {
  const results = useQueries({
    queries: rows.map(row => ({
      queryKey: qk.applicant(jobId, row.applicationId),
      queryFn: ({ signal }: { signal: AbortSignal }) => limit(() => reviewApi.detail(jobId, row.applicationId, signal)),
      staleTime: 60_000,
      enabled: row.matchStatus !== 'PENDING',
    })),
  })
  const map = new Map<number, ApplicantDetail>()
  results.forEach((result, i) => {
    if (result.data) map.set(rows[i].applicationId, result.data)
  })
  return map
}

// ------------------------------------------------------------------------------------------------ evidence strip

export function evidenceSummary(requirements: Requirement[], detail: ApplicantDetail | undefined, unverified: number[]): string {
  const gates = requirements.filter(r => unverified.includes(r.id)).map(r => r.description)
  if (!detail) return gates.length ? `Gate not yet verified: ${gates.join(', ')}` : 'Evidence loading'
  const evidenced = requirements.filter(r => detail.requirements.find(e => e.requirementId === r.id && e.status === 'MATCHED' && e.tier && e.tier !== 'SELF_CLAIMED')).length
  return `${evidenced} of ${requirements.length} requirements evidenced${gates.length ? `; gate not yet verified: ${gates.join(', ')}` : ''}`
}

function cellText(requirement: Requirement, evidence: RequirementEvidence | undefined, gateOpen: boolean): string {
  const tier = evidence?.status === 'NOT_OBSERVED' ? null : evidence?.tier ?? null
  const status = evidence ? (evidence.status === 'MATCHED' ? 'Matched' : evidence.status === 'UNCLEAR' ? 'Unclear' : 'Not observed') : null
  return [requirement.description, status ? `${status} · ${tierLabel(tier, 'staff')}` : null, gateOpen ? 'Gate not yet verified' : requirement.knockout ? 'Gate' : null]
    .filter(Boolean)
    .join(' · ')
}

/**
 * One ladder cell per requirement (must-haves first, a gap before nice-to-haves); open gates outlined in warning.
 * The strip is ONE accessible image with a summary; each cell has a hover tooltip for pointer users.
 */
export function EvidenceStrip({ requirements, detail, unverified, pending }: {
  requirements: Requirement[]
  detail?: ApplicantDetail
  unverified: number[]
  pending?: boolean
}) {
  const firstNice = requirements.findIndex(r => !r.mandatory)
  return (
    <span className="hra-strip" role="img" aria-label={evidenceSummary(requirements, detail, unverified)} data-loading={!detail || undefined} data-pending={pending || undefined}>
      {requirements.map((requirement, i) => {
        const evidence = detail?.requirements.find(e => e.requirementId === requirement.id)
        const gateOpen = unverified.includes(requirement.id)
        const tier = evidence && evidence.status !== 'NOT_OBSERVED' ? evidence.tier : null
        return (
          <Tooltip key={requirement.id} content={cellText(requirement, evidence, gateOpen)} labelOnly delay={150}>
            <span className="hra-strip-cell" data-gap={i === firstNice && i > 0 ? true : undefined} data-unknown={!detail || undefined} aria-hidden="true">
              <EvidenceLadder variant="cell" tier={detail ? tier : null} gateOpen={gateOpen} />
            </span>
          </Tooltip>
        )
      })}
      {pending && <span className="hra-strip-progress" aria-hidden="true" />}
    </span>
  )
}

// ------------------------------------------------------------------------------------------------ coverage cell

/** A number that counts up once and a 48 px bar that sweeps in (M4); "with claims" uses the hatch. */
export function CoverageCell({ value, kind }: { value: number | null; kind: 'verified' | 'claimed' }) {
  const ref = useRef<HTMLSpanElement>(null)
  const [shown, setShown] = useState(() => reduced())
  useEffect(() => {
    if (shown) return
    const id = window.requestAnimationFrame(() => setShown(true))
    return () => window.cancelAnimationFrame(id)
  }, [shown])
  if (value == null) return <span className="subtle" aria-label="No result">—</span>
  const v = Math.max(0, Math.min(100, value))
  return (
    <span ref={ref} className="hra-cov" data-kind={kind} data-shown={shown || undefined}>
      <CountUp value={Math.round(v)} format={n => `${Math.round(n)}%`} />
      <span className="hra-cov-track" aria-hidden="true">
        <span className="hra-cov-fill" style={{ ['--v' as string]: v / 100 }} />
      </span>
    </span>
  )
}

// ------------------------------------------------------------------------------------------------ requirement legend

export function RequirementLegend({ requirements }: { requirements: Requirement[] }) {
  return (
    <ol className="hra-legend-list">
      {requirements.map(r => (
        <li key={r.id}>
          <span className="hra-legend-kind" data-kind={r.knockout && r.mandatory ? 'gate' : r.mandatory ? 'must' : 'nice'}>
            {r.knockout && r.mandatory ? 'Gate' : r.mandatory ? 'Must-have' : 'Nice to have'}
          </span>
          <span>{r.description}</span>
        </li>
      ))}
    </ol>
  )
}
