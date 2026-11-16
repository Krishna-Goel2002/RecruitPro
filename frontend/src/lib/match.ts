/**
 * A quick, honest preview of how a candidate's evidence lines up with a job's requirements (FLOWS-CANDIDATE §4).
 * Exact or alternative text matches only, against resume facts and the candidate's claims; anything fuzzier is left
 * as "not found yet". The employer runs its own matching after an application, so this is labelled as an estimate.
 */
import type { CandidateClaim, EvidenceTier, Requirement, ResumeFact } from '../api/types'
import { tierOrdinal } from '../design/lib/labels'

export interface RequirementMatch {
  requirement: Requirement
  /** Best tier among the matching evidence, or null when nothing matched. */
  tier: EvidenceTier | null
  /** What matched, for the tooltip ("Java · from your resume"). */
  matched: string | null
  from: 'resume' | 'claim' | null
}

function norm(text: string): string {
  return ` ${text.toLowerCase().replace(/[^\p{L}\p{N}+#.]+/gu, ' ').replace(/\s+/g, ' ').trim()} `
}

/** True when `needle` appears in `hay` as whole words (both normalised with surrounding spaces). */
function containsWords(hay: string, needle: string): boolean {
  const n = needle.trim()
  return n.length >= 2 && hay.includes(` ${n} `)
}

function matches(requirement: Requirement, value: string): boolean {
  const v = norm(value)
  if (v.trim().length < 2) return false
  const targets = [requirement.description, ...requirement.alternatives].map(norm)
  return targets.some(t => containsWords(t, v) || containsWords(v, t))
}

interface Evidence {
  value: string
  tier: EvidenceTier
  from: 'resume' | 'claim'
}

export function evidenceFrom(facts: ResumeFact[] | undefined, claims: CandidateClaim[] | undefined): Evidence[] {
  const list: Evidence[] = []
  for (const fact of facts ?? []) {
    if (!['SKILL', 'EXPERIENCE', 'EDUCATION', 'CERTIFICATION'].includes(fact.type)) continue
    list.push({ value: fact.extractedValue || fact.value, tier: (fact.tier as EvidenceTier) ?? 'RESUME_STATED', from: 'resume' })
  }
  for (const claim of claims ?? []) {
    if (claim.verificationStatus === 'REJECTED') continue
    list.push({ value: claim.value, tier: claim.tier, from: 'claim' })
  }
  return list
}

export function matchRequirements(requirements: Requirement[], evidence: Evidence[]): RequirementMatch[] {
  return requirements.map(requirement => {
    let best: Evidence | null = null
    for (const item of evidence) {
      if (!matches(requirement, item.value)) continue
      if (!best || tierOrdinal(item.tier) > tierOrdinal(best.tier)) best = item
    }
    return { requirement, tier: best?.tier ?? null, matched: best?.value ?? null, from: best?.from ?? null }
  })
}

/** A must-have counts as covered at "On your resume" or better; a self-claim alone is worth strengthening. */
export function isCovered(match: RequirementMatch): boolean {
  return tierOrdinal(match.tier) >= 2
}
