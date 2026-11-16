import { forwardRef, useImperativeHandle, useRef, useState, type InputHTMLAttributes } from 'react'
import { cx, useFieldControl } from '../design/components'
import './hra.css'

export interface OtpInputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'value' | 'onChange' | 'size' | 'type'> {
  value: string
  onChange: (value: string) => void
  /** Called once the last digit is typed or pasted (auto-submit). */
  onComplete?: (value: string) => void
  length?: number
  /** Plays a single 4 px shake (reduced motion: none) whenever this number changes. */
  errorKey?: number
}

/**
 * One-time-code input: the visible boxes are drawing only; the real control is ONE native input with
 * autocomplete="one-time-code", so paste, password managers and OTP autofill keep working and screen readers hear a
 * single text field. Generic: used by sign-in (MFA, enrolment) and available to the account pages.
 */
export const OtpInput = forwardRef<HTMLInputElement, OtpInputProps>(function OtpInput(
  { value, onChange, onComplete, length = 6, errorKey = 0, className, disabled, onFocus, onBlur, ...rest },
  ref,
) {
  const local = useRef<HTMLInputElement>(null)
  useImperativeHandle(ref, () => local.current as HTMLInputElement)
  const [focused, setFocused] = useState(false)
  const field = useFieldControl(rest)
  const digits = value.slice(0, length).split('')
  const active = Math.min(digits.length, length - 1)

  return (
    <div className={cx('hra-otp', className)} data-disabled={disabled || undefined} data-invalid={field['aria-invalid'] ? true : undefined}>
      <div key={errorKey} className="hra-otp-boxes" data-shake={errorKey > 0 || undefined} aria-hidden="true">
        {Array.from({ length }, (_, i) => (
          <span
            key={i}
            className="hra-otp-box numeric"
            data-filled={i < digits.length || undefined}
            data-active={focused && i === active && digits.length < length ? true : undefined}
            data-gap={i === length / 2 ? true : undefined}
          >
            {digits[i] ?? ''}
          </span>
        ))}
      </div>
      <input
        ref={local}
        className="hra-otp-input"
        type="text"
        inputMode="numeric"
        autoComplete="one-time-code"
        pattern="[0-9]*"
        maxLength={length}
        spellCheck={false}
        disabled={disabled}
        value={value}
        onFocus={e => {
          setFocused(true)
          onFocus?.(e)
        }}
        onBlur={e => {
          setFocused(false)
          onBlur?.(e)
        }}
        onChange={e => {
          const next = e.target.value.replace(/\D/g, '').slice(0, length)
          onChange(next)
          if (next.length === length && value.length !== length) onComplete?.(next)
        }}
        {...rest}
        id={field.id}
        aria-describedby={field['aria-describedby']}
        aria-invalid={field['aria-invalid']}
      />
    </div>
  )
})
