import { KeyboardEvent, useId, useState } from 'react'
import { CloseIcon } from './Icons'

/** Editable list of short strings (e.g. requirement alternatives). Enter or comma adds; Backspace on empty removes. */
export function ChipsInput({ label, values, onChange, placeholder, maxItems = 10, maxLength = 100 }: {
  label: string
  values: string[]
  onChange: (values: string[]) => void
  placeholder?: string
  maxItems?: number
  maxLength?: number
}) {
  const [draft, setDraft] = useState('')
  const id = useId()

  function add() {
    const value = draft.trim().replace(/,$/, '').trim()
    if (!value) return
    if (!values.some(existing => existing.toLowerCase() === value.toLowerCase()) && values.length < maxItems) onChange([...values, value])
    setDraft('')
  }

  function onKeyDown(event: KeyboardEvent<HTMLInputElement>) {
    if (event.key === 'Enter' || event.key === ',') {
      event.preventDefault()
      add()
    } else if (event.key === 'Backspace' && !draft && values.length) {
      onChange(values.slice(0, -1))
    }
  }

  return (
    <div className="chips-field">
      <label htmlFor={id}>{label}</label>
      <div className="chips-input">
        <ul className="chips" aria-label={`${label}: ${values.length} added`}>
          {values.map(value => (
            <li key={value} className="chip">
              {value}
              <button type="button" onClick={() => onChange(values.filter(item => item !== value))} aria-label={`Remove ${value}`}><CloseIcon /></button>
            </li>
          ))}
        </ul>
        <input
          id={id}
          value={draft}
          maxLength={maxLength}
          placeholder={values.length >= maxItems ? 'Limit reached' : placeholder}
          disabled={values.length >= maxItems}
          onChange={event => setDraft(event.target.value)}
          onKeyDown={onKeyDown}
          onBlur={add}
          aria-describedby={`${id}-hint`}
        />
      </div>
      <small className="sr-only" id={`${id}-hint`}>Press Enter or comma to add. Any one alternative satisfies the requirement.</small>
    </div>
  )
}
