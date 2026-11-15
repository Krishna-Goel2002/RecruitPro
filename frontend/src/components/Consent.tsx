import { useId, type ReactNode } from 'react'

/**
 * Required consent checkbox with plain-language text. The error is tied to the box with aria-describedby and
 * announced when it appears.
 */
export function ConsentCheckbox({
  checked,
  onChange,
  error,
  children,
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  error?: string | null
  children: ReactNode
}) {
  const id = useId()
  const errorId = `${id}-error`
  return (
    <div>
      <div className="consent">
        <input
          id={id}
          type="checkbox"
          checked={checked}
          required
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? errorId : undefined}
          onChange={event => onChange(event.target.checked)}
        />
        <label htmlFor={id}>{children}</label>
      </div>
      {error && (
        <p id={errorId} className="field-error" role="alert">
          {error}
        </p>
      )}
    </div>
  )
}

export const CONSENT_REQUIRED = 'Please tick the consent box first. Nothing is connected without your consent.'
