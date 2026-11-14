import { formatPercent } from '../lib/format'

/**
 * Two numbers per applicant (D5): verified coverage (resume or stronger evidence) and claimed coverage (also
 * counting the candidate's own unverified claims). Plain labels; the bars are decorative.
 */
export function CoverageBars({ verified, claimed, compact = false }: { verified: number | null; claimed: number | null; compact?: boolean }) {
  return (
    <div className={`coverage-bars${compact ? ' compact' : ''}`}>
      <Bar
        kind="verified"
        label="Verified"
        value={verified}
        description="Share of weighted requirements backed by the resume or stronger evidence."
      />
      <Bar
        kind="claimed"
        label="Incl. claims"
        value={claimed}
        description="Share of weighted requirements when the candidate’s own unverified claims are counted too."
      />
    </div>
  )
}

function Bar({ kind, label, value, description }: { kind: 'verified' | 'claimed'; label: string; value: number | null; description: string }) {
  const width = value == null ? 0 : Math.max(0, Math.min(100, value))
  return (
    <div className={`coverage-row ${kind}`} title={description}>
      <span className="coverage-label">{label}</span>
      <span className="coverage-track" aria-hidden="true"><span style={{ width: `${width}%` }} /></span>
      <span className="coverage-value">{formatPercent(value)}</span>
      <span className="sr-only">{description}</span>
    </div>
  )
}
