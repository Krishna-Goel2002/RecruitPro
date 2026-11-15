import type { ReviewDecision, Verdict, VerificationSourceKind } from '../api/types'

/**
 * Plain-language copy for verification results (plan v2 principles 3 and 7). Every chip carries a text label and a
 * symbol, so meaning never depends on colour alone. NOT_FOUND is deliberately neutral: absence of evidence in the
 * connected sources is not evidence against the candidate.
 */
export type VerdictTone = 'positive' | 'partial' | 'neutral' | 'negative' | 'muted'

export type VerdictInfo = { label: string; description: string; tone: VerdictTone; symbol: string }

export const VERDICT_INFO: Record<Verdict, VerdictInfo> = {
  VERIFIED: {
    label: 'Verified',
    description: 'The connected sources support this claim, and the candidate has proven they own the source.',
    tone: 'positive',
    symbol: '✓',
  },
  PARTLY_VERIFIED: {
    label: 'Partly verified',
    description: 'Some support was found, or ownership of the source is not proven.',
    tone: 'partial',
    symbol: '◐',
  },
  NOT_FOUND: {
    label: 'Not found in connected sources',
    description: 'Nothing about this claim was found in the sources the candidate connected. This is neutral and never lowers a score or rank.',
    tone: 'neutral',
    symbol: '○',
  },
  CONTRADICTED: {
    label: 'Contradicted',
    description: 'A source states something that conflicts with this claim. Worth asking about; it is not a finding of dishonesty.',
    tone: 'negative',
    symbol: '!',
  },
  NOT_CHECKABLE: {
    label: 'Not checkable by agents',
    description: 'This kind of claim cannot be checked automatically against this source.',
    tone: 'muted',
    symbol: '–',
  },
}

/** Unknown values (a newer server) fall back to a neutral chip with the raw value humanised. */
export function verdictInfo(verdict: string | null | undefined): VerdictInfo {
  if (verdict && verdict in VERDICT_INFO) return VERDICT_INFO[verdict as Verdict]
  const text = (verdict ?? 'Unknown').replace(/_/g, ' ').toLowerCase()
  return { label: text.charAt(0).toUpperCase() + text.slice(1), description: 'Unrecognised verification result.', tone: 'neutral', symbol: '?' }
}

export const SOURCE_KIND_LABELS: Record<VerificationSourceKind, string> = {
  GITHUB: 'GitHub',
  GITLAB: 'GitLab',
  WEBSITE: 'Website',
  DOCUMENT: 'Document',
  OPEN_BADGE: 'Open Badge',
  REFERENCE: 'Reference',
}

export function sourceKindLabel(kind: string | null | undefined): string {
  if (!kind) return 'Unknown source'
  return SOURCE_KIND_LABELS[kind as VerificationSourceKind] ?? kind.replace(/_/g, ' ').toLowerCase()
}

export const DECISION_LABELS: Record<ReviewDecision, string> = { CONFIRM: 'Confirmed', OVERRULE: 'Overruled' }

/** `sha256:abcdef…` → `sha256:abcdef12…` (first 12 hex characters) for display; the full value goes in a title. */
export function shortHash(hash: string | null | undefined, length = 12): string {
  if (!hash) return '—'
  const [algorithm, hex] = hash.includes(':') ? hash.split(':', 2) : ['', hash]
  const short = hex.length > length ? `${hex.slice(0, length)}…` : hex
  return algorithm ? `${algorithm}:${short}` : short
}

/** Readiness thresholds for switching an organisation to VISIBLE (WP-VBE metrics). */
export const READINESS = { minLabelled: 150, maxFalseCorroboration: 0.02, minKappa: 0.7 } as const

export type ReadinessCheck = { key: string; label: string; met: boolean; detail: string }

export function readinessChecks(metrics: { labelled: number; falseCorroborationRate: number | null; kappa: number | null }): ReadinessCheck[] {
  const fcr = metrics.falseCorroborationRate
  const kappa = metrics.kappa
  return [
    {
      key: 'labelled',
      label: `At least ${READINESS.minLabelled} labelled verdicts`,
      met: metrics.labelled >= READINESS.minLabelled,
      detail: `${metrics.labelled} labelled`,
    },
    {
      key: 'fcr',
      label: 'False-corroboration rate at most 2%',
      met: fcr != null && fcr <= READINESS.maxFalseCorroboration,
      detail: fcr == null ? 'Not measured yet' : `${(fcr * 100).toFixed(1)}%`,
    },
    {
      key: 'kappa',
      label: 'Agreement beyond chance (Cohen’s kappa) at least 0.70',
      met: kappa != null && kappa >= READINESS.minKappa,
      detail: kappa == null ? 'Not measured yet' : kappa.toFixed(2),
    },
  ]
}

/** Review form rule (backend: OVERRULE without a note is 400). Returns an error message or null. */
export function reviewProblem(decision: ReviewDecision | null, note: string): string | null {
  if (!decision) return 'Choose Confirm or Overrule.'
  if (decision === 'OVERRULE' && !note.trim()) return 'Explain why you are overruling this result.'
  if (note.length > 1000) return 'Keep the note under 1000 characters.'
  return null
}

/** Formats a byte count as B / KB / MB. */
export function formatBytes(bytes: number | null | undefined): string {
  if (bytes == null || !Number.isFinite(bytes)) return '—'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}
