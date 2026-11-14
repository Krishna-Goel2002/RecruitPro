import { useId } from 'react'
import { ANCHOR_LEVELS, Anchors } from '../api/types'

/**
 * A 1–4 rating as a radio group; each option shows its behavioural anchor so raters score against the same standard.
 * Arrow keys move between options (native radio behaviour).
 */
export function RatingScale({ legend, value, onChange, anchors, required = false, invalid = false }: {
  legend: string
  value: number | null
  onChange: (value: 1 | 2 | 3 | 4) => void
  anchors: Anchors
  required?: boolean
  invalid?: boolean
}) {
  const name = useId()
  return (
    <fieldset className={`rating-scale${invalid ? ' invalid' : ''}`} aria-required={required} aria-invalid={invalid}>
      <legend>{legend}{required && <span className="required-mark" aria-hidden="true"> *</span>}</legend>
      <div className="rating-options">
        {ANCHOR_LEVELS.map(level => {
          const numeric = Number(level) as 1 | 2 | 3 | 4
          const id = `${name}-${level}`
          return (
            <label key={level} htmlFor={id} className={`rating-option${value === numeric ? ' selected' : ''}`}>
              <input id={id} type="radio" name={name} value={level} checked={value === numeric} onChange={() => onChange(numeric)} />
              <span className="rating-number">{level}</span>
              <span className="rating-anchor">{anchors[level] || '—'}</span>
            </label>
          )
        })}
      </div>
      {invalid && <small className="field-error">Choose a rating.</small>}
    </fieldset>
  )
}

/** Read-only view of a score against its anchors. */
export function AnchorList({ anchors, highlight }: { anchors: Anchors; highlight?: number | null }) {
  return (
    <ol className="anchor-list">
      {ANCHOR_LEVELS.map(level => (
        <li key={level} className={highlight === Number(level) ? 'highlight' : ''}>
          <span className="rating-number">{level}</span>
          <span>{anchors[level] || '—'}</span>
        </li>
      ))}
    </ol>
  )
}
