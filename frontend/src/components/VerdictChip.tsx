import { candidateStatusDescription, candidateStatusLabel, verdictInfo } from '../lib/verification'

/**
 * A verdict as a chip: symbol + words (never colour alone), with the plain-language meaning as a tooltip and for
 * screen readers.
 */
export function VerdictChip({ verdict, describe = false }: { verdict: string; describe?: boolean }) {
  const info = verdictInfo(verdict)
  return (
    <span className={`verdict-chip verdict-${info.tone}`} title={info.description || undefined}>
      <span aria-hidden="true">{info.symbol}</span> {info.label}
      {describe && info.description && <span className="visually-hidden">. {info.description}</span>}
    </span>
  )
}

/** Whether the candidate shared, hid or has yet to review a verdict. */
export function CandidateStatusTag({ status }: { status: string }) {
  const description = candidateStatusDescription(status)
  return (
    <span className={`review-tag review-${status.toLowerCase()}`} title={description || undefined}>
      {candidateStatusLabel(status)}
    </span>
  )
}
