import { createContext, useContext, useId, type AriaAttributes, type FieldsetHTMLAttributes, type ReactNode } from 'react'
import { CircleAlert, Icon } from '../icons'
import { cx } from './cx'

interface FieldContextValue {
  id: string
  describedBy?: string
  invalid: boolean
  required?: boolean
}

const FieldContext = createContext<FieldContextValue | null>(null)

/** Props a control spreads to join its Field (id, aria-describedby, aria-invalid). Explicit props win. */
export function useFieldControl(props: { id?: string; 'aria-describedby'?: string; 'aria-invalid'?: AriaAttributes['aria-invalid'] }) {
  const ctx = useContext(FieldContext)
  return {
    id: props.id ?? ctx?.id,
    'aria-describedby': cx(props['aria-describedby'], ctx?.describedBy) || undefined,
    'aria-invalid': props['aria-invalid'] ?? (ctx?.invalid ? true : undefined),
    required: ctx?.required,
  }
}

export interface FieldProps {
  label: ReactNode
  children: ReactNode
  hint?: ReactNode
  error?: ReactNode
  /** Writes "(optional)" after the label. Required fields are not starred. */
  optional?: boolean
  /** Sets `required` on the control (native validation off by default in forms; use noValidate). */
  required?: boolean
  id?: string
  className?: string
  /** Visually hide the label (it stays the accessible name). */
  hideLabel?: boolean
}

/** Label → control → hint → error (§9.4). Wires id, aria-describedby and aria-invalid into the child control. */
export function Field({ label, children, hint, error, optional, required, id, className, hideLabel }: FieldProps) {
  const auto = useId()
  const controlId = id ?? `f${auto}`
  const hintId = hint ? `${controlId}-hint` : undefined
  const errorId = error ? `${controlId}-error` : undefined
  return (
    <FieldContext.Provider value={{ id: controlId, describedBy: cx(errorId, hintId) || undefined, invalid: !!error, required }}>
      <div className={cx('ui-field', className)} data-invalid={error ? true : undefined}>
        <label htmlFor={controlId} className={cx('ui-field-label', hideLabel && 'sr-only')}>
          {label}
          {optional && <span className="ui-field-optional"> (optional)</span>}
        </label>
        {children}
        {hint && (
          <p id={hintId} className="ui-field-hint">
            {hint}
          </p>
        )}
        {error && (
          <p id={errorId} className="ui-field-error">
            <Icon icon={CircleAlert} size={14} />
            <span>{error}</span>
          </p>
        )}
      </div>
    </FieldContext.Provider>
  )
}

export interface FieldsetProps extends FieldsetHTMLAttributes<HTMLFieldSetElement> {
  legend: ReactNode
  hint?: ReactNode
  error?: ReactNode
}

/** Group of checkboxes/radios with a legend, hint and error. */
export function Fieldset({ legend, hint, error, children, className, ...rest }: FieldsetProps) {
  const id = useId()
  return (
    <fieldset
      className={cx('ui-fieldset', className)}
      aria-describedby={cx(error && `${id}-e`, hint && `${id}-h`) || undefined}
      aria-invalid={error ? true : undefined}
      {...rest}
    >
      <legend className="ui-field-label">{legend}</legend>
      {hint && (
        <p id={`${id}-h`} className="ui-field-hint">
          {hint}
        </p>
      )}
      <div className="ui-fieldset-body">{children}</div>
      {error && (
        <p id={`${id}-e`} className="ui-field-error">
          <Icon icon={CircleAlert} size={14} />
          <span>{error}</span>
        </p>
      )}
    </fieldset>
  )
}
