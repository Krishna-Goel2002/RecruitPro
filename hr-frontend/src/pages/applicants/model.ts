import type { QueryClient } from '@tanstack/react-query'
import type { ApplicantDetail, ApplicantSummary, CandidateClaim, DecisionAction, EvidenceTier, Page, RequirementEvidence } from '../../api/types'
import { EVIDENCE_TIERS } from '../../api/types'

/* Pure helpers for the applicant page (no React): gates, ordering, claim ↔ requirement links, decision copy. */

export const asTier = (value: string | null | undefined): EvidenceTier | null =>
  value && (EVIDENCE_TIERS as string[]).includes(value) ? (value as EvidenceTier) : null

/** An open ranking gate: a knockout requirement not yet matched at resume level or stronger. */
export function isGateOpen(req: RequirementEvidence, detail?: Pick<ApplicantDetail, 'unverifiedKnockouts'>): boolean {
  if (!req.knockout) return false
  if (detail?.unverifiedKnockouts?.length) return detail.unverifiedKnockouts.includes(req.requirementId)
  return req.status !== 'MATCHED' || req.tier == null || req.tier === 'SELF_CLAIMED'
}

/** Open gates first, then must-haves, then nice-to-haves; server order inside each group. */
export function orderRequirements(reqs: RequirementEvidence[], detail?: Pick<ApplicantDetail, 'unverifiedKnockouts'>): RequirementEvidence[] {
  const rank = (r: RequirementEvidence) => (isGateOpen(r, detail) ? 0 : r.mandatory ? 1 : 2)
  return reqs.map((r, i) => ({ r, i })).sort((a, b) => rank(a.r) - rank(b.r) || a.i - b.i).map(x => x.r)
}

export const STATUS_WORD: Record<RequirementEvidence['status'], string> = {
  MATCHED: 'Matched',
  UNCLEAR: 'Unclear',
  NOT_OBSERVED: 'Not observed',
}

export const PROVENANCE_WORD: Record<string, string> = {
  RESUME: 'Resume text',
  CANDIDATE_CLAIM: 'Candidate’s own claim',
  CORROBORATED: 'Claim checked by a reviewer',
  WORK_SAMPLE: 'Scored work sample',
  INTERVIEW: 'Structured interview',
}

const STOP = new Set(['and', 'or', 'the', 'of', 'in', 'with', 'a', 'an', 'to', 'for', 'on', 'at', 'years', 'year', 'experience', 'skills', 'knowledge'])
const tokens = (text: string) =>
  text.toLowerCase().normalize('NFKD').replace(/[^\p{L}\p{N}+#.]+/gu, ' ').split(' ').map(t => t.replace(/\.$/, '')).filter(t => t.length > 1 && !STOP.has(t))

/**
 * The candidate claim that most plausibly backs a requirement (shown inline under the requirement so the reviewer can
 * verify it where they read it). Same type, and every content word of the claim appears in the requirement or one of
 * its alternatives. A heuristic for convenience only: the Claims tab always lists every claim.
 */
export function relatedClaim(req: RequirementEvidence, claims: CandidateClaim[]): CandidateClaim | null {
  const haystack = new Set([req.description, ...(req.alternatives ?? [])].flatMap(tokens))
  let best: CandidateClaim | null = null
  let bestScore = 0
  for (const claim of claims) {
    if (claim.claimType && req.type && claim.claimType !== req.type && !(claim.claimType === 'SKILL' && req.type === 'EXPERIENCE')) continue
    const words = tokens(claim.value).filter(w => !/^\d{4}$/.test(w))
    if (!words.length) continue
    const hit = words.filter(w => haystack.has(w)).length
    const score = hit / words.length
    if (score >= 0.6 && score > bestScore) {
      best = claim
      bestScore = score
    }
  }
  return best
}

export function claimStatus(claim: CandidateClaim): 'VERIFIED' | 'REJECTED' | 'UNVERIFIED' {
  const s = claim.verificationStatus
  if (s === 'VERIFIED' || s === 'REJECTED' || s === 'UNVERIFIED') return s
  if (claim.verifiedAt) return claim.tier === 'SELF_CLAIMED' ? 'REJECTED' : 'VERIFIED'
  return 'UNVERIFIED'
}

// --------------------------------------------------------------------------------------------- decisions

export type ActionSpec = {
  label: string
  /** Button text in the confirmation dialog. */
  confirm: string
  title: string
  description: string
  commentRequired: boolean
  tone: 'primary' | 'danger'
  /** The server action that reverses this one (offered as Undo in the toast). */
  inverse?: DecisionAction
  doneTitle: string
}

export const ACTIONS: Record<DecisionAction, ActionSpec> = {
  SHORTLIST: {
    label: 'Shortlist',
    confirm: 'Shortlist and reveal',
    title: 'Shortlist and reveal identity?',
    description: 'Shortlisting reveals who the candidate is to everyone reviewing this job.',
    commentRequired: false,
    tone: 'primary',
    inverse: 'UNDO_SHORTLIST',
    doneTitle: 'Shortlisted · identity revealed',
  },
  MOVE_TO_INTERVIEW: {
    label: 'Move to interview',
    confirm: 'Move to interview',
    title: 'Move to interview without scheduling?',
    description: 'Scheduling the first interview does this automatically. Use this when the interview is arranged outside the product.',
    commentRequired: false,
    tone: 'primary',
    doneTitle: 'Moved to interviewing',
  },
  SELECT: {
    label: 'Select',
    confirm: 'Select candidate',
    title: 'Select this candidate?',
    description: 'Selected is the final stage here; the hire is handed off to your offer process. The candidate is notified.',
    commentRequired: false,
    tone: 'primary',
    doneTitle: 'Selected · handed off to your offer process',
  },
  UNDO_SHORTLIST: {
    label: 'Undo shortlist',
    confirm: 'Undo shortlist',
    title: 'Undo the shortlist?',
    description: 'The application returns to Submitted. The identity stays revealed. Say why for the decision history.',
    commentRequired: true,
    tone: 'primary',
    doneTitle: 'Shortlist undone',
  },
  REOPEN: {
    label: 'Reopen',
    confirm: 'Reopen application',
    title: 'Reopen this application?',
    description: 'The application returns to Submitted and the candidate is told it is being considered again. Say why for the decision history.',
    commentRequired: true,
    tone: 'primary',
    doneTitle: 'Application reopened',
  },
  REJECT: {
    label: 'Reject',
    confirm: 'Reject',
    title: 'Reject this application?',
    description: 'The candidate is notified that the application was not progressed. You can reopen it later with a reason.',
    commentRequired: false,
    tone: 'danger',
    inverse: 'REOPEN',
    doneTitle: 'Not progressed',
  },
}

export const REJECT_REASONS = [
  'Must-haves not evidenced.',
  'Another candidate is further along.',
  'Experience level does not fit the role.',
  'Position filled or paused.',
]

/** The one primary action for a status (the decision bar shows it as the primary button). */
export function primaryFor(status: ApplicantDetail['applicationStatus'], allowed: DecisionAction[], canSchedule: boolean): DecisionAction | 'SCHEDULE' | null {
  if (status === 'SUBMITTED' && allowed.includes('SHORTLIST')) return 'SHORTLIST'
  if (status === 'SHORTLISTED') return canSchedule ? 'SCHEDULE' : allowed.includes('MOVE_TO_INTERVIEW') ? 'MOVE_TO_INTERVIEW' : null
  if (status === 'INTERVIEWING' && allowed.includes('SELECT')) return 'SELECT'
  if (status === 'REJECTED' && allowed.includes('REOPEN')) return 'REOPEN'
  return null
}

export const HISTORY_WORD: Record<string, string> = {
  SHORTLIST: 'Shortlisted',
  REJECT: 'Not progressed',
  UNDO_SHORTLIST: 'Shortlist undone',
  REOPEN: 'Reopened',
  MOVE_TO_INTERVIEW: 'Moved to interview',
  SELECT: 'Selected',
  WITHDRAW: 'Withdrawn by the candidate',
  COMMENT_UPDATED: 'Review note',
  REVIEW_COMMENT: 'Review note',
  IDENTITY_REVEALED: 'Identity revealed',
}

// --------------------------------------------------------------------------------------------- prev / next

/**
 * The applicant order the reviewer came from: `state.order` from the ranking table when given, else the newest cached
 * ranking page of this job that contains the applicant. Returns null when the position is unknown.
 */
export function listPosition(queryClient: QueryClient, jobId: number, applicationId: number, stateOrder?: unknown): { ids: number[]; index: number } | null {
  if (Array.isArray(stateOrder) && stateOrder.every(n => typeof n === 'number')) {
    const ids = stateOrder as number[]
    const index = ids.indexOf(applicationId)
    if (index >= 0) return { ids, index }
  }
  const entries = queryClient.getQueriesData<Page<ApplicantSummary>>({ queryKey: ['applicants', jobId, 'list'] })
  let best: { ids: number[]; index: number; at: number } | null = null
  for (const [key, data] of entries) {
    const ids = data?.content?.map(row => row.applicationId) ?? []
    const index = ids.indexOf(applicationId)
    if (index < 0) continue
    const at = queryClient.getQueryState(key)?.dataUpdatedAt ?? 0
    if (!best || at > best.at) best = { ids, index, at }
  }
  return best ? { ids: best.ids, index: best.index } : null
}
