import type { ReactNode } from 'react'
import { formatNumber } from '../lib/format'
import { cx } from './cx'

export interface MeterProps {
  label: ReactNode
  value: number
  max: number
  /** Target tick (2 px, default ink) with its own label. */
  target?: number
  targetLabel?: ReactNode
  tone?: 'accent' | 'success' | 'warning' | 'danger' | 'neutral'
  /** Text for the value ("118 / 150"); defaults to value / max. */
  valueText?: string
  className?: string
}

/** Meter (§9.29): 6 px track, tone fill, optional target tick, labels "118 / 150" and the target. */
export function Meter({ label, value, max, target, targetLabel, tone = 'accent', valueText, className }: MeterProps) {
  const pct = Math.max(0, Math.min(1, value / max))
  const text = valueText ?? `${formatNumber(value)} / ${formatNumber(max)}`
  return (
    <div className={cx('ui-meter', className)} data-tone={tone}>
      <div className="ui-meter-labels">
        <span className="ui-meter-label">{label}</span>
        <span className="ui-meter-value numeric">{text}</span>
      </div>
      <div
        className="ui-meter-track"
        role="meter"
        aria-valuemin={0}
        aria-valuemax={max}
        aria-valuenow={value}
        aria-valuetext={text}
        aria-label={typeof label === 'string' ? label : undefined}
      >
        <span className="ui-meter-fill" style={{ ['--v' as string]: pct }} />
        {target != null && <span className="ui-meter-target" style={{ left: `${(target / max) * 100}%` }} aria-hidden="true" />}
      </div>
      {targetLabel && <p className="ui-meter-target-label">{targetLabel}</p>}
    </div>
  )
}
