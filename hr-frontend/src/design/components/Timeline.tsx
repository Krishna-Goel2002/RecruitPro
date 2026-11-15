import type { ReactNode } from 'react'
import { formatDate, relativeTime } from '../lib/format'
import { cx } from './cx'

export interface TimelineItem {
  id: string | number
  title: ReactNode
  /** Actor, outcome… (body-sm muted). */
  meta?: ReactNode
  at?: string | Date | null
  state?: 'done' | 'current' | 'future'
  /** A comment, rendered as a quote. */
  children?: ReactNode
}

/** Timeline (§9.24): a 1 px hairline with 8 px dots (done filled, current accent with a halo, future hollow). */
export function Timeline({ items, className, label }: { items: TimelineItem[]; className?: string; label?: string }) {
  return (
    <ol className={cx('ui-timeline', className)} aria-label={label}>
      {items.map(item => {
        const date = item.at ? new Date(item.at) : null
        return (
          <li key={item.id} className="ui-timeline-item" data-state={item.state ?? 'done'} aria-current={item.state === 'current' ? 'step' : undefined}>
            <span className="ui-timeline-dot" aria-hidden="true" />
            <div className="ui-timeline-content">
              <p className="ui-timeline-title">{item.title}</p>
              {(item.meta || date) && (
                <p className="ui-timeline-meta">
                  {item.meta}
                  {item.meta && date && <span className="ui-sep" aria-hidden="true">·</span>}
                  {date && (
                    <time dateTime={date.toISOString()} title={formatDate(date, { dateStyle: 'medium', timeStyle: 'short' })}>
                      {relativeTime(date)}
                    </time>
                  )}
                </p>
              )}
              {item.children && <div className="ui-timeline-body">{item.children}</div>}
            </div>
          </li>
        )
      })}
    </ol>
  )
}
