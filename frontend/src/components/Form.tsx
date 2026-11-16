import { forwardRef, useState, type InputHTMLAttributes } from 'react'
import { Input } from '../design/components'
import { Check, Circle, Eye, EyeOff, Icon } from '../design/icons'
import { checkPassword } from '../lib/password'
import './form.css'

/**
 * Password input with a show/hide toggle inside the field. Use inside a design-system <Field>, which wires the id,
 * aria-describedby and aria-invalid into the input.
 */
export const PasswordInput = forwardRef<
  HTMLInputElement,
  Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'size' | 'onChange'> & {
    value: string
    onChange: (value: string) => void
    autoComplete: 'current-password' | 'new-password'
    controlSize?: 'md' | 'lg'
  }
>(function PasswordInput({ value, onChange, autoComplete, maxLength = 72, controlSize = 'md', ...rest }, ref) {
  const [visible, setVisible] = useState(false)
  return (
    <Input
      ref={ref}
      {...rest}
      controlSize={controlSize}
      type={visible ? 'text' : 'password'}
      value={value}
      maxLength={maxLength}
      autoComplete={autoComplete}
      spellCheck={false}
      onChange={event => onChange(event.target.value)}
      trailing={
        <button
          type="button"
          className="c-reveal"
          aria-pressed={visible}
          aria-label={visible ? 'Hide password' : 'Show password'}
          onClick={() => setVisible(current => !current)}
        >
          <Icon icon={visible ? EyeOff : Eye} size={16} />
        </button>
      }
    />
  )
})

/** Live checklist for the password policy: SVG ticks (never glyphs), state also in words for screen readers. */
export function PasswordChecklist({ password, id }: { password: string; id?: string }) {
  return (
    <ul className="c-pw-checks" id={id} aria-label="Password requirements">
      {checkPassword(password).map(check => (
        <li key={check.id} data-met={check.met || undefined}>
          <Icon icon={check.met ? Check : Circle} size={12} strokeWidth={check.met ? 2.25 : 1.75} />
          <span>{check.label}</span>
          <span className="sr-only">{check.met ? ' (met)' : ' (not met yet)'}</span>
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

const FOCUSABLE = 'input:not([disabled]):not([type="hidden"]), select:not([disabled]), textarea:not([disabled]), button:not([disabled]), [tabindex]'

/**
 * After a failed submit, move focus to the first invalid control of `form` (WCAG 3.3.1). A group marked invalid
 * (a `<fieldset aria-invalid>` of checkboxes) is not focusable itself, so focus goes to its first control.
 */
export function focusFirstInvalid(form: HTMLElement | null) {
  window.setTimeout(() => {
    const invalid = form?.querySelector<HTMLElement>('[aria-invalid="true"]')
    if (!invalid) return
    const target = invalid.matches(FOCUSABLE) ? invalid : invalid.querySelector<HTMLElement>(FOCUSABLE)
    ;(target ?? invalid).focus()
  }, 0)
}
