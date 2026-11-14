import { useId, useState, type ReactNode } from 'react'
import { checkPassword } from '../lib/password'

export interface ControlProps {
  id: string
  'aria-describedby'?: string
  'aria-invalid'?: boolean
  required?: boolean
}

interface FieldProps {
  label: ReactNode
  hint?: ReactNode
  error?: string | null
  required?: boolean
  optional?: boolean
  className?: string
  children: (control: ControlProps) => ReactNode
}

/** A labelled form control with hint and error text wired up through aria-describedby. */
export function Field({ label, hint, error, required = false, optional = false, className, children }: FieldProps) {
  const id = useId()
  const hintId = `${id}-hint`
  const errorId = `${id}-error`
  const describedBy = [hint ? hintId : null, error ? errorId : null].filter(Boolean).join(' ') || undefined
  return (
    <div className={`field ${className ?? ''}`}>
      <label htmlFor={id}>
        {label}
        {optional && <span className="optional"> (optional)</span>}
      </label>
      {children({ id, 'aria-describedby': describedBy, 'aria-invalid': error ? true : undefined, required: required || undefined })}
      {hint && (
        <div id={hintId} className="field-hint">
          {hint}
        </div>
      )}
      {error && (
        <p id={errorId} className="field-error">
          {error}
        </p>
      )}
    </div>
  )
}

/** Password input with a show/hide toggle. */
export function PasswordInput({
  control,
  value,
  onChange,
  autoComplete,
  maxLength = 72,
}: {
  control: ControlProps
  value: string
  onChange: (value: string) => void
  autoComplete: 'current-password' | 'new-password'
  maxLength?: number
}) {
  const [visible, setVisible] = useState(false)
  return (
    <div className="password-input">
      <input
        {...control}
        type={visible ? 'text' : 'password'}
        value={value}
        maxLength={maxLength}
        autoComplete={autoComplete}
        onChange={event => onChange(event.target.value)}
      />
      <button
        type="button"
        className="text-button inline"
        aria-pressed={visible}
        aria-label={visible ? 'Hide password' : 'Show password'}
        onClick={() => setVisible(current => !current)}
      >
        {visible ? 'Hide' : 'Show'}
      </button>
    </div>
  )
}

/** Live checklist for the password policy. */
export function PasswordChecklist({ password, id }: { password: string; id?: string }) {
  return (
    <ul className="password-checklist" id={id} aria-label="Password requirements">
      {checkPassword(password).map(check => (
        <li key={check.id} className={check.met ? 'met' : ''}>
          <span aria-hidden="true">{check.met ? '✓' : '○'}</span> {check.label}
          <span className="visually-hidden">{check.met ? ' (met)' : ' (not met yet)'}</span>
        </li>
      ))}
    </ul>
  )
}

/** Field errors returned by the API, keyed by field name. */
export function fieldErrorMap(errors: { field: string; message: string }[]): Record<string, string> {
  const map: Record<string, string> = {}
  for (const error of errors) if (!map[error.field]) map[error.field] = error.message
  return map
}
