import { useId, type HTMLAttributes, type ReactNode } from 'react'
import { cx } from './cx'

export interface SectionProps extends Omit<HTMLAttributes<HTMLElement>, 'title'> {
  title: ReactNode
  description?: ReactNode
  actions?: ReactNode
  /** Heading level for the title (default 2). */
  level?: 2 | 3
}

/**
 * Section (§9.14): h2 + optional description + actions, then content. Sections are separated by a hairline and
 * 32 px of space; there is no box. This replaces "cards".
 */
export function Section({ title, description, actions, level = 2, className, children, id, ...rest }: SectionProps) {
  const auto = useId()
  const headingId = id ? `${id}-title` : `s${auto}`
  const H = level === 2 ? 'h2' : 'h3'
  return (
    <section className={cx('ui-section', className)} aria-labelledby={headingId} id={id} {...rest}>
      <header className="ui-section-header">
        <div className="ui-section-heading">
          <H id={headingId} className={level === 2 ? 't-h2' : 't-h3'}>
            {title}
          </H>
          {description && <p className="ui-section-desc">{description}</p>}
        </div>
        {actions && <div className="ui-section-actions">{actions}</div>}
      </header>
      {children}
    </section>
  )
}

/** Section-level header without the <section> wrapper (inside dialogs, drawers, panels). */
export function SectionHeader({ title, description, actions, level = 3 }: Omit<SectionProps, 'children'>) {
  const H = level === 2 ? 'h2' : 'h3'
  return (
    <header className="ui-section-header" data-inline="">
      <div className="ui-section-heading">
        <H className={level === 2 ? 't-h2' : 't-h3'}>{title}</H>
        {description && <p className="ui-section-desc">{description}</p>}
      </div>
      {actions && <div className="ui-section-actions">{actions}</div>}
    </header>
  )
}
