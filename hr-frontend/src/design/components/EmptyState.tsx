import type { ReactNode } from 'react'
import { Icon, Inbox, type IconComponent } from '../icons'
import { cx } from './cx'

export interface EmptyStateProps {
  icon?: IconComponent
  /** One line: "No applicants yet". */
  title: ReactNode
  /** One sentence: why it is empty or what happens next. */
  description?: ReactNode
  /** One action (Button or link). */
  action?: ReactNode
  /** Tighter spacing for table bodies and side rails. */
  compact?: boolean
  headingLevel?: 2 | 3
  className?: string
}

/** Empty state (§9.25): left-aligned, 20 px subtle icon, one h3 line, one sentence, one action. No illustrations. */
export function EmptyState({ icon = Inbox, title, description, action, compact, headingLevel = 3, className }: EmptyStateProps) {
  const H = headingLevel === 2 ? 'h2' : 'h3'
  return (
    <div className={cx('ui-empty', className)} data-compact={compact || undefined}>
      <Icon icon={icon} size={20} className="ui-empty-icon" />
      <div className="ui-empty-text">
        <H className="t-h3">{title}</H>
        {description && <p className="ui-empty-desc">{description}</p>}
        {action && <div className="ui-empty-action">{action}</div>}
      </div>
    </div>
  )
}
