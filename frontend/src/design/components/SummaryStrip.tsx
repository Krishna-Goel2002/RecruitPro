import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { CountUp } from './CountUp'
import { cx } from './cx'

export type SummaryTone = 'neutral' | 'attention' | 'danger' | 'success'

export interface SummaryItem {
  key: string
  label: ReactNode
  /** null/undefined shows "—" (not known yet). */
  value: number | null | undefined
  format?: (n: number) => string
  /** One short line under the number ("since your last visit"). */
  hint?: ReactNode
  /** "attention" and "danger" add a marker and a tinted rule; never colour alone (the hint says why). */
  tone?: SummaryTone
  /** Makes the tile a router link. */
  to?: string
  /** Makes the tile a toggle button (for example a filter); `pressed` is its state. */
  onSelect?: () => void
  pressed?: boolean
  /** Accessible name when the visible label is not enough ("Show drafts: 2"). */
  actionLabel?: string
}

export interface SummaryStripProps {
  items: SummaryItem[]
  /** Region name for assistive tech ("Jobs at a glance"). */
  label: string
  className?: string
}

const fmtDefault = (n: number) => new Intl.NumberFormat().format(Math.round(n))

/**
 * Summary strip (HR-UX main-page pass): a row of 2–5 numbers that answer "how is it going and what needs me?" at the
 * top of a list page. Tiles count up on first view and arrive with the list stagger; tiles with `to` or `onSelect`
 * lift on hover and press. "attention" tiles carry a marker so the eye goes there first.
 */
export function SummaryStrip({ items, label, className }: SummaryStripProps) {
  if (items.length === 0) return null
  return (
    <ul className={cx('ui-summary ui-stagger', className)} aria-label={label} role="list">
      {items.map(item => {
        const fmt = item.format ?? fmtDefault
        const body = (
          <>
            <span className="ui-summary-label">
              {(item.tone === 'attention' || item.tone === 'danger') && <span className="ui-summary-mark" aria-hidden="true" />}
              {item.label}
            </span>
            <span className="ui-summary-value">
              {item.value == null ? <span className="numeric">—</span> : <CountUp value={item.value} format={fmt} />}
            </span>
            {item.hint && <span className="ui-summary-hint">{item.hint}</span>}
          </>
        )
        const common = { className: 'ui-summary-tile', 'data-tone': item.tone && item.tone !== 'neutral' ? item.tone : undefined }
        return (
          <li key={item.key} className="ui-summary-item">
            {item.to ? (
              <Link {...common} className={cx(common.className, 'ui-lift')} to={item.to} aria-label={item.actionLabel}>
                {body}
              </Link>
            ) : item.onSelect ? (
              <button
                type="button"
                {...common}
                className={cx(common.className, 'ui-lift')}
                onClick={item.onSelect}
                aria-pressed={item.pressed ?? undefined}
                aria-label={item.actionLabel}
              >
                {body}
              </button>
            ) : (
              <div {...common}>{body}</div>
            )}
          </li>
        )
      })}
    </ul>
  )
}
