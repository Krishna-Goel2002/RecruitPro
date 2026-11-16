import { useId, type KeyboardEvent, type ReactNode } from 'react'
import type { Anchors, AnchorLevel } from '../api/types'
import { cx } from '../design/components'
import './staff-ui.css'

const LEVELS: AnchorLevel[] = ['1', '2', '3', '4']
const DEFAULT_WORDS: Record<AnchorLevel, string> = { 1: 'Weak', 2: 'Partial', 3: 'Solid', 4: 'Strong' }

/**
 * Anchored 1–4 rating (work-sample rubric criteria, interview questions): four option cards, each showing its number
 * and the anchor text, so the rater reads the scale instead of guessing it. Native radios underneath (arrow keys move
 * between options); the digits 1–4 also choose while focus is anywhere inside the group.
 */
export function AnchoredRating({ legend, description, anchors, value, onChange, invalid, required, disabled, hideLegend, className }: {
  legend: ReactNode
  description?: ReactNode
  anchors?: Partial<Anchors> | null
  value: number | null
  onChange: (value: 1 | 2 | 3 | 4) => void
  invalid?: boolean
  required?: boolean
  disabled?: boolean
  hideLegend?: boolean
  className?: string
}) {
  const name = useId()
  const errorId = `${name}-err`

  function onKeyDown(event: KeyboardEvent<HTMLFieldSetElement>) {
    if (disabled || event.ctrlKey || event.metaKey || event.altKey) return
    const target = event.target as HTMLElement
    if (target.tagName === 'TEXTAREA' || (target.tagName === 'INPUT' && (target as HTMLInputElement).type === 'text')) return
    if (['1', '2', '3', '4'].includes(event.key)) {
      event.preventDefault()
      const next = Number(event.key) as 1 | 2 | 3 | 4
      onChange(next)
      const input = event.currentTarget.querySelector<HTMLInputElement>(`input[value="${next}"]`)
      input?.focus()
    }
  }

  return (
    <fieldset
      role="radiogroup"
      className={cx('sx-rating', className)}
      data-invalid={invalid || undefined}
      aria-invalid={invalid || undefined}
      aria-describedby={invalid ? errorId : undefined}
      disabled={disabled}
      onKeyDown={onKeyDown}
    >
      <legend className={hideLegend ? 'sr-only' : 'sx-rating-legend'}>
        {legend}
        {required && <span className="sr-only"> (required)</span>}
      </legend>
      {description && <p className="sx-rating-desc">{description}</p>}
      <div className="sx-rating-options">
        {LEVELS.map(level => {
          const n = Number(level)
          const anchor = anchors?.[level]?.trim() || DEFAULT_WORDS[level]
          const checked = value === n
          return (
            <label key={level} className="sx-rating-option" data-checked={checked || undefined} data-level={level}>
              <input
                type="radio"
                name={name}
                value={level}
                checked={checked}
                onChange={() => onChange(n as 1 | 2 | 3 | 4)}
                required={required}
              />
              <span className="sx-rating-num numeric" aria-hidden="true">{level}</span>
              <span className="sx-rating-anchor">
                <span className="sr-only">{level} of 4: </span>
                {anchor}
              </span>
            </label>
          )
        })}
      </div>
      {invalid && <p id={errorId} className="sx-rating-error">Choose a rating from 1 to 4.</p>}
    </fieldset>
  )
}
