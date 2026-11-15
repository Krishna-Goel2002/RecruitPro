import type { ReactNode } from 'react'
import { ArrowDown, ArrowUp, Icon } from '../icons'
import { CountUp } from './CountUp'
import { cx } from './cx'

export interface StatProps {
  label: ReactNode
  value: number
  format?: (n: number) => string
  /** Signed change; shown with an arrow and a word, never colour alone. */
  delta?: { value: string; direction: 'up' | 'down'; good?: boolean }
  hint?: ReactNode
  /** Count up once on first view (M4). Default true. */
  animate?: boolean
}

/** Stat (§9.14): label above an h2-sized tabular value; unboxed. Put several in a <StatRow>. */
export function Stat({ label, value, format, delta, hint, animate = true }: StatProps) {
  return (
    <div className="ui-stat">
      <dt className="ui-stat-label">{label}</dt>
      <dd className="ui-stat-value">
        {animate ? <CountUp value={value} format={format} /> : <span className="numeric">{format ? format(value) : value}</span>}
        {delta && (
          <span className="ui-stat-delta" data-good={delta.good ?? undefined}>
            <Icon icon={delta.direction === 'up' ? ArrowUp : ArrowDown} size={12} />
            {delta.value}
          </span>
        )}
      </dd>
      {hint && <dd className="ui-stat-hint">{hint}</dd>}
    </div>
  )
}

/** A row of stats separated by vertical hairlines. */
export function StatRow({ children, className }: { children: ReactNode; className?: string }) {
  return <dl className={cx('ui-stat-row', className)}>{children}</dl>
}
