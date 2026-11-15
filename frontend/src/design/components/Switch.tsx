import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react'
import { cx } from './cx'

export interface SwitchProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type'> {
  label: ReactNode
  description?: ReactNode
  /** Label before the track (settings lists) instead of after it. */
  labelFirst?: boolean
}

/**
 * Switch (§9.7): 28×16 track, 12 px knob with the settle ease. It changes a setting immediately; when the change
 * needs a Save press, use a Checkbox instead. Native checkbox with role="switch".
 */
export const Switch = forwardRef<HTMLInputElement, SwitchProps>(function Switch({ label, description, labelFirst, className, id, ...rest }, ref) {
  const auto = useId()
  const inputId = id ?? `sw${auto}`
  return (
    <label className={cx('ui-switch', className)} htmlFor={inputId} data-label-first={labelFirst || undefined} data-disabled={rest.disabled || undefined}>
      <input ref={ref} id={inputId} type="checkbox" role="switch" className="ui-switch-input" aria-describedby={description ? `${inputId}-d` : undefined} {...rest} />
      <span className="ui-switch-track" aria-hidden="true">
        <span className="ui-switch-thumb" />
      </span>
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
