import { forwardRef, useEffect, useId, useRef, type InputHTMLAttributes, type ReactNode } from 'react'
import { cx } from './cx'
import { mergeRefs } from './hooks'

export interface CheckboxProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label?: ReactNode
  description?: ReactNode
  /** Tri-state (table header "select all"). */
  indeterminate?: boolean
}

/** 16 px checkbox (§9.7); the tick draws with stroke-dashoffset. The whole row (≥ 32 px) is the label. */
export const Checkbox = forwardRef<HTMLInputElement, CheckboxProps>(function Checkbox(
  { label, description, indeterminate, className, id, ...rest },
  ref,
) {
  const local = useRef<HTMLInputElement>(null)
  const auto = useId()
  const inputId = id ?? `cb${auto}`
  useEffect(() => {
    if (local.current) local.current.indeterminate = !!indeterminate
  }, [indeterminate])
  const box = (
    <span className="ui-check-box">
      <input
        ref={mergeRefs(ref, local)}
        id={inputId}
        type="checkbox"
        className="ui-check-input"
        aria-describedby={description ? `${inputId}-d` : rest['aria-describedby']}
        {...rest}
      />
      <svg className="ui-check-mark" viewBox="0 0 16 16" aria-hidden="true" focusable="false">
        <path className="ui-check-tick" d="M3.75 8.25 6.75 11.25 12.25 5" pathLength={1} />
        <path className="ui-check-dash" d="M4.5 8h7" />
      </svg>
    </span>
  )
  if (!label) return <span className={cx('ui-check', className)}>{box}</span>
  return (
    <label className={cx('ui-check', className)} htmlFor={inputId} data-disabled={rest.disabled || undefined}>
      {box}
      <span className="ui-check-text">
        <span className="ui-check-label">{label}</span>
        {description && (
          <span id={`${inputId}-d`} className="ui-check-desc">
            {description}
          </span>
        )}
      </span>
    </label>
  )
})
