import { forwardRef, useId, type InputHTMLAttributes, type ReactNode } from 'react'
import { Icon, type IconComponent } from '../icons'
import { cx } from './cx'
import { useRadioGroup } from './Radio'

export interface RadioCardProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'type' | 'value'> {
  value: string
  label: ReactNode
  description: ReactNode
  icon?: IconComponent
}

/**
 * Radio option with a multi-line description (§9.7): only where each choice needs explaining (verification mode,
 * source kind). Hairline box, radius 6; selected = accent border + selected background. Use inside RadioGroup.
 */
export const RadioCard = forwardRef<HTMLInputElement, RadioCardProps>(function RadioCard(
  { value, label, description, icon, className, id, ...rest },
  ref,
) {
  const group = useRadioGroup()
  const auto = useId()
  const inputId = id ?? `rc${auto}`
  const groupProps = group ? { name: group.name, checked: group.value === value, onChange: () => group.onChange?.(value) } : {}
  return (
    <label className={cx('ui-radio-card', className)} htmlFor={inputId}>
      <input ref={ref} id={inputId} type="radio" className="ui-radio-input" value={value} aria-describedby={`${inputId}-d`} {...groupProps} {...rest} />
      <span className="ui-radio-card-text">
        <span className="ui-radio-card-title">
          {icon && <Icon icon={icon} size={16} />}
          {label}
        </span>
        <span id={`${inputId}-d`} className="ui-radio-card-desc">
          {description}
        </span>
      </span>
    </label>
  )
})
