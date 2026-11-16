import { useId, type ReactNode } from 'react'
import { Checkbox } from '../design/components'
import { CircleAlert, Icon } from '../design/icons'

/**
 * Required consent checkbox. The sentence passed as children is versioned server-side (handbook 03 §7.5): never
 * reword it here. The error is tied to the box with aria-describedby and announced when it appears.
 */
export function ConsentCheckbox({
  checked,
  onChange,
  error,
  children,
}: {
  checked: boolean
  onChange: (checked: boolean) => void
  error?: string | null
  children: ReactNode
}) {
  const id = useId()
  const errorId = `${id}-error`
  return (
    <div className="c-consent" data-invalid={error ? true : undefined}>
      <Checkbox
        id={id}
        checked={checked}
        label={children}
        aria-invalid={error ? true : undefined}
        aria-describedby={error ? errorId : undefined}
        onChange={event => onChange(event.target.checked)}
      />
      {error && (
        <p id={errorId} className="ui-field-error" role="alert">
          <Icon icon={CircleAlert} size={14} />
          <span>{error}</span>
        </p>
      )}
    </div>
  )
}

export const CONSENT_REQUIRED = 'Please tick the consent box first. Nothing is connected without your consent.'
