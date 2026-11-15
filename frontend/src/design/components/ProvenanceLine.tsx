import type { HTMLAttributes, ReactNode } from 'react'
import { ArrowUpRight, Icon, type IconComponent } from '../icons'
import { formatDate, relativeTime } from '../lib/format'
import { cx } from './cx'

export interface ProvenanceLineProps {
  icon?: IconComponent
  /** Source kind ("GitHub", "Reference", "Resume"). */
  source: ReactNode
  /** Actor or verifier ("checked by Priya S.", "agent"). */
  actor?: ReactNode
  at?: string | Date | null
  href?: string
  hrefLabel?: string
  className?: string
}

/** Who/what/when line (§9.13), directly under the quote it qualifies: icon · source · actor · time · View source ↗. */
export function ProvenanceLine({ icon, source, actor, at, href, hrefLabel = 'View source', className }: ProvenanceLineProps) {
  const date = at ? new Date(at) : null
  return (
    <p className={cx('ui-provenance', className)}>
      {icon && <Icon icon={icon} size={14} />}
      <span>{source}</span>
      {actor && (
        <>
          <span className="ui-sep" aria-hidden="true">·</span>
          <span>{actor}</span>
        </>
      )}
      {date && (
        <>
          <span className="ui-sep" aria-hidden="true">·</span>
          <time dateTime={date.toISOString()} title={formatDate(date, { dateStyle: 'medium', timeStyle: 'short' })}>
            {relativeTime(date)}
          </time>
        </>
      )}
      {href && (
        <a className="ui-provenance-link" href={href} target="_blank" rel="noreferrer noopener">
          {hrefLabel}
          <Icon icon={ArrowUpRight} size={12} />
          <span className="sr-only"> (opens in a new tab)</span>
        </a>
      )}
    </p>
  )
}

/** Evidence quote (§9.13): 2 px strong left rule on a sunken background; body-lg for candidates. */
export function Quote({ children, large, className, ...rest }: HTMLAttributes<HTMLQuoteElement> & { large?: boolean }) {
  return (
    <blockquote className={cx('ui-quote', className)} data-large={large || undefined} {...rest}>
      {children}
    </blockquote>
  )
}
