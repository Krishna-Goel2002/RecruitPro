/*
 * Audience label maps (DESIGN_SYSTEM §3.6, §3.7). Same visual encoding in both portals; only the words differ.
 * Components take an `audience` prop and read from here, so a page never hand-writes a tier or verdict label.
 */
export type Audience = 'candidate' | 'staff'

export type Tier = 'SELF_CLAIMED' | 'RESUME_STATED' | 'CORROBORATED' | 'DEMONSTRATED' | 'INTERVIEW_VERIFIED'
export const TIERS: readonly Tier[] = ['SELF_CLAIMED', 'RESUME_STATED', 'CORROBORATED', 'DEMONSTRATED', 'INTERVIEW_VERIFIED']

export type Verdict = 'VERIFIED' | 'PARTLY_VERIFIED' | 'NOT_FOUND' | 'CONTRADICTED' | 'NOT_CHECKABLE'
export const VERDICTS: readonly Verdict[] = ['VERIFIED', 'PARTLY_VERIFIED', 'NOT_FOUND', 'CONTRADICTED', 'NOT_CHECKABLE']

export function tierOrdinal(tier: Tier | string | null | undefined): number {
  const i = TIERS.indexOf(tier as Tier)
  return i < 0 ? 0 : i + 1
}

const TIER_LABEL: Record<Audience, Record<Tier | 'NONE', string>> = {
  candidate: {
    SELF_CLAIMED: 'Your claim',
    RESUME_STATED: 'On your resume',
    CORROBORATED: 'Corroborated',
    DEMONSTRATED: 'Demonstrated',
    INTERVIEW_VERIFIED: 'Verified in interview',
    NONE: 'Not found yet',
  },
  staff: {
    SELF_CLAIMED: 'Claimed',
    RESUME_STATED: 'Resume',
    CORROBORATED: 'Corroborated',
    DEMONSTRATED: 'Demonstrated',
    INTERVIEW_VERIFIED: 'Interview-verified',
    NONE: 'No evidence',
  },
}

export const TIER_DESCRIPTION: Record<Tier, string> = {
  SELF_CLAIMED: 'Stated by the candidate; nothing checked yet.',
  RESUME_STATED: 'Found in the resume text.',
  CORROBORATED: 'Supported by an outside source or a reviewer.',
  DEMONSTRATED: 'Shown in a work sample or in code the candidate owns.',
  INTERVIEW_VERIFIED: 'Confirmed by interviewers against a rubric.',
}

export function tierLabel(tier: Tier | string | null | undefined, audience: Audience = 'staff'): string {
  const known = TIERS.includes(tier as Tier) ? (tier as Tier) : 'NONE'
  return TIER_LABEL[audience][known]
}

export const VERDICT_LABEL: Record<Verdict, string> = {
  VERIFIED: 'Verified',
  PARTLY_VERIFIED: 'Partly verified',
  NOT_FOUND: 'Not found in connected sources',
  CONTRADICTED: 'Contradicted',
  NOT_CHECKABLE: 'Not checkable by agents',
}

export const VERDICT_DESCRIPTION: Record<Verdict, string> = {
  VERIFIED: 'The source supports the claim and belongs to the candidate.',
  PARTLY_VERIFIED: 'The source supports part of the claim, or ownership is not proven.',
  NOT_FOUND: 'Nothing in the connected sources mentions this. It does not count against anyone.',
  CONTRADICTED: 'A source disagrees with the claim. Worth asking about.',
  NOT_CHECKABLE: 'Agents cannot check this kind of claim; a person can.',
}

export function verdictLabel(v: Verdict | string): string {
  return v in VERDICT_LABEL ? VERDICT_LABEL[v as Verdict] : v
}
