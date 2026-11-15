import type { ReactNode } from 'react'
import { CircleAlert, CircleCheck, Icon, Info, TriangleAlert, X, type IconComponent } from '../icons'
import { cx } from './cx'

export type AlertTone = 'info' | 'success' | 'warning' | 'danger' | 'neutral'
const ICONS: Record<AlertTone, IconComponent> = { info: Info, success: CircleCheck, warning: TriangleAlert, danger: CircleAlert, neutral: Info }

export interface AlertProps {
  tone?: AlertTone
  title?: ReactNode
  children?: ReactNode
  /** One action (a Button or a link). */
  action?: ReactNode
  onDismiss?: () => void
  /** banner: a single line across the page (verify email, shadow mode). */
  variant?: 'inline' | 'banner'
  /** role="alert" for errors that appear after an action; default: none (static content). */
  live?: boolean
  className?: string
}

/** Inline alert / banner (§9.19): 3 px tone bar + icon + text on the tone background, radius 6. */
export function Alert({ tone = 'info', title, children, action, onDismiss, variant = 'inline', live, className }: AlertProps) {
  return (
    <div className={cx('ui-alert', className)} data-tone={tone} data-variant={variant} role={live ? (tone === 'danger' ? 'alert' : 'status') : undefined}>
      <Icon icon={ICONS[tone]} size={16} className="ui-alert-icon" />
      <div className="ui-alert-text">
        {title && <p className="ui-alert-title">{title}</p>}
        {children && <div className="ui-alert-body">{children}</div>}
      </div>
      {action && <div className="ui-alert-action">{action}</div>}
      {onDismiss && (
        <button type="button" className="ui-alert-dismiss" aria-label="Dismiss" onClick={onDismiss}>
          <Icon icon={X} size={14} />
        </button>
      )}
    </div>
  )
}
