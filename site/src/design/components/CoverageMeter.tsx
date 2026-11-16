import { useEffect, useRef, useState } from 'react'
import { Icon, Info } from '../icons'
import { reveal, reduced } from '../motion'
import { CountUp } from './CountUp'
import { cx } from './cx'
import { Tooltip } from './Tooltip'

export interface CoverageMeterProps {
  /** Share of weighted requirements with verified evidence, 0–100. */
  verified: number
  /** Share including candidate claims, 0–100 (≥ verified). */
  claimed: number
  /** Table form: 64 px bars, no row labels (the column headers carry them). */
  compact?: boolean
  /** Info tooltip after the labels; defaults to the "not a probability" sentence. Pass null to hide. */
  note?: string | null
  className?: string
}

const pct = (n: number) => `${Math.round(n)}%`
const clamp = (n: number) => Math.max(0, Math.min(100, n))

/**
 * Coverage meter (§3.9, §9.12, M4): two 4 px bars. Verified = solid accent; With claims = a 45° hatch drawn behind
 * the verified fill up to the claimed value. On first view the bars scale in (420 ms decelerate), the numbers count
 * up (700 ms) and the claimed hatch follows 80 ms later.
 */
export function CoverageMeter({ verified, claimed, compact, note, className }: CoverageMeterProps) {
  const v = clamp(verified)
  const c = Math.max(v, clamp(claimed))
  const ref = useRef<HTMLDivElement>(null)
  const [state, setState] = useState<'idle' | 'play' | 'done'>(() => (reduced() ? 'done' : 'idle'))
  useEffect(() => {
    const el = ref.current
    if (!el || state !== 'idle') return
    return reveal(el, () => setState('play'))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])
  const text = note === undefined ? 'Coverage is the share of weighted requirements with evidence. It is not a probability of success.' : note
  return (
    <div
      ref={ref}
      className={cx('ui-coverage', className)}
      data-compact={compact || undefined}
      data-state={state}
      role="group"
      aria-label={`Coverage: ${pct(v)} verified, ${pct(c)} with claims`}
    >
      <div className="ui-coverage-row">
        {!compact && <span className="ui-coverage-label">Verified</span>}
        <span className="ui-coverage-track" aria-hidden="true">
          <span className="ui-coverage-fill" style={{ ['--v' as string]: v / 100 }} />
        </span>
        <span className="ui-coverage-value" aria-hidden="true">
          {state === 'idle' ? pct(0) : <CountUp value={v} format={pct} />}
        </span>
      </div>
      <div className="ui-coverage-row">
        {!compact && (
          <span className="ui-coverage-label">
            With claims
            {text && (
              <Tooltip content={text} labelOnly>
                <button type="button" className="ui-coverage-info" aria-label={`About coverage: ${text}`}>
                  <Icon icon={Info} size={12} />
                </button>
              </Tooltip>
            )}
          </span>
        )}
        <span className="ui-coverage-track" aria-hidden="true">
          <span className="ui-coverage-hatch" style={{ ['--v' as string]: c / 100 }} />
          <span className="ui-coverage-fill" style={{ ['--v' as string]: v / 100 }} />
        </span>
        <span className="ui-coverage-value" aria-hidden="true">
          {state === 'idle' ? pct(0) : <CountUp value={c} format={pct} />}
        </span>
      </div>
    </div>
  )
}
