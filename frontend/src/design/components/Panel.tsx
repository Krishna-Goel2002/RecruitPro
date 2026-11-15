import { forwardRef, type HTMLAttributes, type ReactNode } from 'react'
import { cx } from './cx'

export interface PanelProps extends Omit<HTMLAttributes<HTMLDivElement>, 'title'> {
  title?: ReactNode
  actions?: ReactNode
  /** danger: the account-deletion zone (the only red-framed box). */
  tone?: 'default' | 'danger'
  /** Remove inner padding (e.g. a table inside). */
  flush?: boolean
}

/**
 * Panel (§9.14): a hairline box, radius 6, padding 16. ONLY for things that are independently actionable or
 * floating (principle 2): a danger zone, a dropzone result, a selectable item. Everything else is a Section.
 */
export const Panel = forwardRef<HTMLDivElement, PanelProps>(function Panel({ title, actions, tone = 'default', flush, className, children, ...rest }, ref) {
  return (
    <div ref={ref} className={cx('ui-panel', className)} data-tone={tone} data-flush={flush || undefined} {...rest}>
      {(title || actions) && (
        <div className="ui-panel-header">
          {title && <h3 className="t-h3">{title}</h3>}
          {actions && <div className="ui-panel-actions">{actions}</div>}
        </div>
      )}
      {children}
    </div>
  )
})
