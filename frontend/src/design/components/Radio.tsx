import { createContext, forwardRef, useContext, useId, type InputHTMLAttributes, type ReactNode } from 'react'
import { cx } from './cx'

interface RadioGroupContextValue {
  name: string
  value?: string
  onChange?: (value: string) => void
  disabled?: boolean
}
const RadioGroupContext = createContext<RadioGroupContextValue | null>(null)
export const useRadioGroup = () => useContext(RadioGroupContext)

export interface RadioGroupProps {
  legend: ReactNode
  value?: string
  onChange?: (value: string) => void
  name?: string
  hint?: ReactNode
  error?: ReactNode
  disabled?: boolean
  /** 'column' (default) or 'row'. */
  direction?: 'column' | 'row'
  hideLegend?: boolean
  children: ReactNode
  className?: string
}

/** A <fieldset> of radios sharing a name and value. Arrow keys move between options natively. */
export function RadioGroup({ legend, value, onChange, name, hint, error, disabled, direction = 'column', hideLegend, children, className }: RadioGroupProps) {
  const auto = useId()
  return (
    <RadioGroupContext.Provider value={{ name: name ?? `rg${auto}`, value, onChange, disabled }}>
      <fieldset
        className={cx('ui-fieldset', className)}
        aria-describedby={cx(error && `${auto}-e`, hint && `${auto}-h`) || undefined}
        disabled={disabled}
      >
        <legend className={cx('ui-field-label', hideLegend && 'sr-only')}>{legend}</legend>
        {hint && (
          <p id={`${auto}-h`} className="ui-field-hint">
            {hint}
          </p>
        )}
        <div className="ui-fieldset-body" data-direction={direction}>
          {children}
        </div>
        {error && (
          <p id={`${auto}-e`} className="ui-field-error">
            {error}
          </p>
        )}
      </fieldset>
    </RadioGroupContext.Provider>
  )
}

export interface RadioProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'value'> {
  value: string
  label: ReactNode
  description?: ReactNode
}

/** 16 px radio (§9.7). Inside a RadioGroup it is controlled by the group. */
export const Radio = forwardRef<HTMLInputElement, RadioProps>(function Radio({ value, label, description, className, id, ...rest }, ref) {
  const group = useRadioGroup()
  const auto = useId()
  const inputId = id ?? `r${auto}`
  const groupProps = group
    ? { name: group.name, checked: group.value === value, onChange: () => group.onChange?.(value) }
    : {}
  return (
    <label className={cx('ui-check', className)} htmlFor={inputId} data-disabled={rest.disabled || group?.disabled || undefined}>
      <span className="ui-check-box">
        <input
          ref={ref}
          id={inputId}
          type="radio"
          className="ui-radio-input"
          value={value}
          aria-describedby={description ? `${inputId}-d` : undefined}
          {...groupProps}
          {...rest}
        />
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
