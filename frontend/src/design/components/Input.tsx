import { forwardRef, type InputHTMLAttributes, type ReactNode } from 'react'
import { cx } from './cx'
import { useFieldControl } from './Field'

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, 'size'> {
  /** Affix inside the field before the text (₹, https://, a search icon). */
  leading?: ReactNode
  /** Affix inside the field after the text (unit, a clear button). */
  trailing?: ReactNode
  controlSize?: 'sm' | 'md' | 'lg'
}

/** Text input (§9.3). Inside a <Field> it picks up id, aria-describedby and aria-invalid automatically. */
export const Input = forwardRef<HTMLInputElement, InputProps>(function Input(
  { leading, trailing, controlSize = 'md', className, type = 'text', ...rest },
  ref,
) {
  const field = useFieldControl(rest)
  const input = (
    <input
      ref={ref}
      type={type}
      className={cx('ui-input', leading || trailing ? 'ui-input-bare' : className)}
      data-size={leading || trailing ? undefined : controlSize}
      {...rest}
      id={field.id}
      aria-describedby={field['aria-describedby']}
      aria-invalid={field['aria-invalid']}
      required={rest.required ?? field.required}
    />
  )
  if (!leading && !trailing) return input
  return (
    <div className={cx('ui-input-wrap', className)} data-size={controlSize} data-disabled={rest.disabled || undefined}>
      {leading && <span className="ui-affix" data-side="leading">{leading}</span>}
      {input}
      {trailing && <span className="ui-affix" data-side="trailing">{trailing}</span>}
    </div>
  )
})
