import { cloneElement, ReactElement, ReactNode, useId } from 'react'

type ControlProps = { id?: string; 'aria-invalid'?: boolean; 'aria-describedby'?: string; required?: boolean }

/** Label + control + hint/error, wired with ids so screen readers announce all three. */
export function Field({ label, required, hint, error, className, children }: {
  label: ReactNode
  required?: boolean
  hint?: ReactNode
  error?: string | null
  className?: string
  children: ReactElement<ControlProps>
}) {
  const id = useId()
  const messageId = `${id}-message`
  const control = cloneElement(children, {
    id,
    'aria-invalid': Boolean(error),
    'aria-describedby': error || hint ? messageId : undefined,
    required: children.props.required ?? required,
  })
  return (
    <div className={`field${className ? ` ${className}` : ''}`}>
      <label htmlFor={id}>{label} {required && <span className="required-mark" aria-hidden="true">*</span>}</label>
      {control}
      {error ? <small className="field-error" id={messageId}>{error}</small> : hint ? <small className="field-hint" id={messageId}>{hint}</small> : null}
    </div>
  )
}

/** Accessible on/off switch (a checkbox styled as a toggle). */
export function Toggle({ checked, onChange, label, description, disabled }: {
  checked: boolean
  onChange: (checked: boolean) => void
  label: ReactNode
  description?: ReactNode
  disabled?: boolean
}) {
  const id = useId()
  return (
    <div className={`toggle-row${disabled ? ' disabled' : ''}`}>
      <input id={id} type="checkbox" role="switch" className="toggle-input" checked={checked} disabled={disabled} onChange={event => onChange(event.target.checked)} aria-describedby={description ? `${id}-description` : undefined} />
      <label htmlFor={id} className="toggle-label">
        <span className="toggle-track" aria-hidden="true"><span /></span>
        <span className="toggle-text">{label}</span>
      </label>
      {description && <p className="toggle-description" id={`${id}-description`}>{description}</p>}
    </div>
  )
}
