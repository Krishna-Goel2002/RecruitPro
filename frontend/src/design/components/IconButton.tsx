import { forwardRef, type ButtonHTMLAttributes } from 'react'
import { Icon, type IconComponent } from '../icons'
import { cx } from './cx'
import { Spinner } from './Spinner'
import { Tooltip } from './Tooltip'

export interface IconButtonProps extends Omit<ButtonHTMLAttributes<HTMLButtonElement>, 'children'> {
  /** Accessible name; also shown as the tooltip. Required. */
  label: string
  icon: IconComponent
  size?: 'sm' | 'md'
  variant?: 'ghost' | 'secondary'
  loading?: boolean
  /** Hide the tooltip (e.g. when a visible label sits right next to the button). */
  noTooltip?: boolean
  tooltipSide?: 'top' | 'bottom'
}

/** Square icon-only button (§9.2): 28/32 px, ghost by default, tooltip + aria-label, 44 px hit area on touch. */
export const IconButton = forwardRef<HTMLButtonElement, IconButtonProps>(function IconButton(
  { label, icon, size = 'md', variant = 'ghost', loading, noTooltip, tooltipSide = 'top', className, type = 'button', ...rest },
  ref,
) {
  const button = (
    <button
      ref={ref}
      type={type}
      aria-label={label}
      aria-busy={loading || undefined}
      className={cx('ui-icon-btn', className)}
      data-size={size}
      data-variant={variant}
      {...rest}
    >
      {loading ? <Spinner size={16} /> : <Icon icon={icon} size={16} />}
    </button>
  )
  if (noTooltip) return button
  return (
    <Tooltip content={label} labelOnly side={tooltipSide}>
      {button}
    </Tooltip>
  )
})
