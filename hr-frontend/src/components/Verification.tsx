import { useId } from 'react'
import type { IntegrityNote, Verdict, VerdictReason, VerificationEvidence } from '../api/types'
import { VERDICTS } from '../api/types'
import { formatDateTime, safeHttpUrl } from '../lib/format'
import { shortHash, verdictInfo } from '../lib/verification'

/** Verdict chip: symbol + text (never colour alone); the description is available to screen readers and on hover. */
export function VerdictChip({ verdict }: { verdict: string }) {
  const info = verdictInfo(verdict)
  return (
    <span className={`verdict-chip verdict-${info.tone}`} title={info.description} data-verdict={verdict}>
      <span className="verdict-symbol" aria-hidden="true">{info.symbol}</span>
      {info.label}
      <span className="sr-only">. {info.description}</span>
    </span>
  )
}

export function OwnershipIndicator({ proven }: { proven: boolean }) {
  return proven ? (
    <span className="ownership-indicator proven"><span aria-hidden="true">🔒</span> Ownership proven</span>
  ) : (
    <span className="ownership-indicator unproven" title="The candidate has not proven they own this source, so the result is capped at Partly verified.">
      <span aria-hidden="true">○</span> Ownership not proven
    </span>
  )
}

/**
 * Reasons, integrity notes, the suggested interview question and evidence of one verdict. `evidence === null` means the
 * server withheld it (blind-first before reveal, or a shadow item of an unrevealed application): only the count shows.
 */
export function VerdictBody({ reasons, integrityNotes, probeQuestion, evidence, evidenceCount, hiddenEvidenceText }: {
  reasons: VerdictReason[]
  integrityNotes: IntegrityNote[]
  probeQuestion: string | null
  evidence: VerificationEvidence[] | null
  evidenceCount: number
  hiddenEvidenceText: string
}) {
  return (
    <div className="verdict-body">
      {reasons.length > 0 && (
        <div className="verdict-block">
          <h5>Why</h5>
          <ul className="verdict-reasons">
            {reasons.map((reason, index) => (
              <li key={index}>
                {reason.text}
                {reason.quote && <q className="verdict-quote">{reason.quote}</q>}
                {reason.evidenceKeys.length > 0 && <span className="item-meta"> (evidence {reason.evidenceKeys.join(', ')})</span>}
              </li>
            ))}
          </ul>
        </div>
      )}

      {integrityNotes.length > 0 && (
        <div className="verdict-block integrity-notes">
          <h5>Worth asking about</h5>
          <p className="item-meta">Observations for a conversation, not accusations. There are often ordinary explanations.</p>
          <ul>
            {integrityNotes.map((note, index) => <li key={`${note.code}-${index}`}>{note.text}</li>)}
          </ul>
        </div>
      )}

      {probeQuestion && (
        <div className="verdict-block probe-question">
          <h5>Suggested interview question</h5>
          <p>{probeQuestion}</p>
        </div>
      )}

      <div className="verdict-block">
        <h5>Evidence</h5>
        {evidence == null ? (
          <p className="muted-copy">
            {evidenceCount === 1 ? '1 evidence item.' : `${evidenceCount} evidence items.`} {hiddenEvidenceText}
          </p>
        ) : evidence.length === 0 ? (
          <p className="muted-copy">No evidence items are linked to this result.</p>
        ) : (
          <EvidenceList items={evidence} />
        )}
      </div>
    </div>
  )
}

export function EvidenceList({ items }: { items: VerificationEvidence[] }) {
  return (
    <ul className="evidence-items">
      {items.map(item => {
        const url = safeHttpUrl(item.url)
        const title = item.title || item.url || item.kind
        return (
          <li key={item.id}>
            <span className="evidence-key" aria-label={`Evidence ${item.key}`}>{item.key}</span>
            <span className="evidence-main">
              {url ? <a href={url} target="_blank" rel="noopener noreferrer nofollow">{title} ↗<span className="sr-only"> (opens in a new tab)</span></a> : <strong>{title}</strong>}
              <span className="item-meta">
                {item.kind.replace(/_/g, ' ').toLowerCase()} · retrieved {formatDateTime(item.retrievedAt)} · <span title={item.contentHash}>{shortHash(item.contentHash)}</span>
              </span>
              {item.excerpt && <span className="evidence-excerpt">{item.excerpt}</span>}
            </span>
          </li>
        )
      })}
    </ul>
  )
}

/** Radio group with the five verdict classes (used to label shadow verdicts). Arrow keys move between options natively. */
export function VerdictPicker({ value, onChange, legend, invalid = false, disabled = false }: {
  value: Verdict | null
  onChange: (value: Verdict) => void
  legend: string
  invalid?: boolean
  disabled?: boolean
}) {
  const name = useId()
  return (
    <fieldset className={`verdict-picker${invalid ? ' invalid' : ''}`} disabled={disabled} aria-invalid={invalid || undefined}>
      <legend>{legend}</legend>
      <div className="verdict-options">
        {VERDICTS.map(option => {
          const info = verdictInfo(option)
          return (
            <label key={option} className={`verdict-option verdict-${info.tone}${value === option ? ' selected' : ''}`}>
              <input type="radio" name={name} value={option} checked={value === option} onChange={() => onChange(option)} />
              <span aria-hidden="true" className="verdict-symbol">{info.symbol}</span> {info.label}
            </label>
          )
        })}
      </div>
    </fieldset>
  )
}
