import { forwardRef, type SelectHTMLAttributes } from 'react'
import { ChevronDown, Icon } from '../icons'
import { cx } from './cx'
import { useFieldControl } from './Field'

export interface SelectProps extends Omit<SelectHTMLAttributes<HTMLSelectElement>, 'size'> {
  controlSize?: 'sm' | 'md' | 'lg'
}

/** Native <select> styled as an input with a 16 px chevron (§9.5). Keeps platform accessibility. */
export const Select = forwardRef<HTMLSelectElement, SelectProps>(function Select({ controlSize = 'md', className, children, ...rest }, ref) {
  const field = useFieldControl(rest)
  return (
    <div className={cx('ui-select', className)} data-size={controlSize}>
      <select
        ref={ref}
        className="ui-input"
        data-size={controlSize}
        {...rest}
        id={field.id}
        aria-describedby={field['aria-describedby']}
        aria-invalid={field['aria-invalid']}
        required={rest.required ?? field.required}
      >
        {children}
      </select>
      <Icon icon={ChevronDown} size={16} className="ui-select-chevron" />
    </div>
  )
})
