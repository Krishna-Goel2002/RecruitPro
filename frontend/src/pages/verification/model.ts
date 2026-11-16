/** Small pure helpers for the Strengthen page (no React). */
import type { LedgerClaim, SourceKind, VerdictSummary, VerificationSource } from '../../api/types'
import { Award, FileText, Github, Gitlab, Globe, type IconComponent } from '../../design/icons'
import type { Verdict } from '../../design/lib/labels'
import { isRunActive } from '../../lib/verification'

export const KIND_ICON: Record<string, IconComponent> = {
  GITHUB: Github,
  GITLAB: Gitlab,
  WEBSITE: Globe,
  DOCUMENT: FileText,
  OPEN_BADGE: Award,
  REFERENCE: FileText,
}

export function kindIcon(kind: string | null | undefined): IconComponent {
  return (kind && KIND_ICON[kind]) || Globe
}

export const KIND_ORDER: SourceKind[] = ['GITHUB', 'GITLAB', 'WEBSITE', 'DOCUMENT', 'OPEN_BADGE']

export const KIND_HINT: Record<SourceKind, string> = {
  GITHUB: 'Public repositories and contributions. Signing in proves the account is yours.',
  GITLAB: 'Public projects on gitlab.com, proven with a code in your profile bio.',
  WEBSITE: 'Your portfolio or personal site, proven with a DNS record, a file or a code on the page.',
  DOCUMENT: 'A certificate or transcript (PDF, DOCX, PPTX, up to 15 MB).',
  OPEN_BADGE: "A digital badge, proven by its issuer's signature.",
}

export const subjectKey = (claim: { subjectType: string; subjectId: number }) => `${claim.subjectType}:${claim.subjectId}`

export interface FlatVerdict extends VerdictSummary {
  claim: LedgerClaim
}

/** Every current verdict with its claim, in ledger (reading) order. */
export function flatVerdicts(ledger: LedgerClaim[] | undefined): FlatVerdict[] {
  return (ledger ?? []).flatMap(claim => claim.verdicts.map(v => ({ ...v, claim })))
}

export function awaitingVerdicts(ledger: LedgerClaim[] | undefined): FlatVerdict[] {
  return flatVerdicts(ledger).filter(v => v.candidateStatus === 'AWAITING_CANDIDATE')
}

export const SUPPORTING: Verdict[] = ['VERIFIED', 'PARTLY_VERIFIED']

export function ledgerStats(ledger: LedgerClaim[] | undefined) {
  const claims = ledger ?? []
  const all = flatVerdicts(claims)
  return {
    claims: claims.length,
    withOutside: claims.filter(c => c.verdicts.some(v => SUPPORTING.includes(v.verdict as Verdict))).length,
    awaiting: all.filter(v => v.candidateStatus === 'AWAITING_CANDIDATE').length,
    shared: all.filter(v => v.candidateStatus === 'SHARED').length,
  }
}

/**
 * Which claims a running source is likely checking (FLOWS-CANDIDATE §14.5): code hosts read skills only; other
 * sources read every claim. Claims that already have a verdict from this source are always included.
 */
export function willCheck(source: VerificationSource, claim: LedgerClaim): boolean {
  if (claim.verdicts.some(v => v.sourceLabel === source.displayLabel)) return true
  if (source.kind === 'GITHUB' || source.kind === 'GITLAB') return claim.claimType === 'SKILL'
  return true
}

export function runningSources(sources: VerificationSource[] | undefined): VerificationSource[] {
  return (sources ?? []).filter(s => isRunActive(s.lastRun?.status))
}

/** Counts of the current verdicts produced by one source, for the "first check finished" summary. */
export function verdictCountsFor(ledger: LedgerClaim[] | undefined, sourceLabel: string): Partial<Record<Verdict, number>> {
  const counts: Partial<Record<Verdict, number>> = {}
  for (const v of flatVerdicts(ledger)) {
    if (v.sourceLabel !== sourceLabel) continue
    const key = v.verdict as Verdict
    counts[key] = (counts[key] ?? 0) + 1
  }
  return counts
}
