import { useEffect, useLayoutEffect, useRef, useState } from 'react'
import { TIER_DESCRIPTION, TIERS, tierLabel, tierOrdinal, type Audience, type Tier } from '../lib/labels'
import { reveal, reduced } from '../motion'
import { cx } from './cx'
import { Tooltip } from './Tooltip'

export interface EvidenceLadderProps {
  tier: Tier | string | null | undefined
  audience?: Audience
  /** chip: rungs + label · cell: 10×18 strip cell for tables · legend: all five tiers explained. */
  variant?: 'chip' | 'cell' | 'legend'
  /** An open ranking gate (must-have not yet evidenced): warning outline + lock in the cell. */
  gateOpen?: boolean
  /** The tier before an upgrade: only the new rungs fill, the top one lands with the settle ease (M5). */
  previousTier?: Tier | string | null
  /** 'view' (default for chip/legend): fill once on first view. false: static. */
  animate?: 'view' | false
  /** Requirement text for the cell tooltip ("Java 17 · Corroborated · ranking gate"). */
  requirement?: string
  /** Legend: hide the one-line descriptions. */
  compact?: boolean
  className?: string
}

const RUNG_H = [6, 8, 10, 12, 14]

function Rungs({ ordinal, from, play, claimed }: { ordinal: number; from: number; play: boolean; claimed: boolean }) {
  return (
    <svg className="ui-ladder-rungs" width="28" height="14" viewBox="0 0 28 14" aria-hidden="true" focusable="false">
      {RUNG_H.map((h, i) => {
        const n = i + 1
        const x = i * 6
        if (n === 1 && claimed) {
          return <rect key={i} className="ui-rung" data-state="claimed" x={x + 0.5} y={14 - h + 0.5} width={3} height={h - 1} rx={0.5} />
        }
        const filled = n <= ordinal
        const isNew = play && filled && n > from
        return (
          <g key={i}>
            <rect className="ui-rung-bg" x={x} y={14 - h} width={4} height={h} rx={1} />
            {filled && (
              <rect
                className="ui-rung"
                data-state="filled"
                data-new={isNew || undefined}
                data-top={isNew && n === ordinal ? true : undefined}
                style={isNew ? { animationDelay: `${(n - from - 1) * 50}ms` } : undefined}
                x={x}
                y={14 - h}
                width={4}
                height={h}
                rx={1}
              />
            )}
          </g>
        )
      })}
    </svg>
  )
}

/**
 * Evidence ladder (§3.7, §9.11). Fill level is the encoding: number of solid rungs = tier ordinal; Self-claimed is a
 * dashed hollow first rung ("nothing checked yet"); no evidence = five empty rungs. role="img" with a full label.
 */
export function EvidenceLadder({
  tier, audience = 'staff', variant = 'chip', gateOpen, previousTier, animate, requirement, compact, className,
}: EvidenceLadderProps) {
  const ordinal = tierOrdinal(tier)
  const claimed = tier === 'SELF_CLAIMED'
  const label = tierLabel(tier, audience)
  const aria = `Evidence: ${label.toLowerCase()}${ordinal ? ` (tier ${ordinal} of 5)` : ''}${gateOpen ? '; ranking gate open' : ''}`

  // Animation: on first view the rungs above `previousTier` (or above nothing) fill bottom-up; later tier changes
  // fill only the new rungs. The initial state is computed during render so nothing flashes.
  const ref = useRef<HTMLSpanElement>(null)
  const mode = animate ?? (variant === 'cell' ? false : 'view')
  const start = previousTier !== undefined ? tierOrdinal(previousTier) : claimed ? 1 : 0
  const [play, setPlay] = useState<{ from: number; key: number } | null>(() =>
    mode !== false && variant !== 'cell' && ordinal > start && !reduced() ? { from: start, key: 1 } : null,
  )
  const [waiting, setWaiting] = useState(() => play !== null)
  const lastOrdinal = useRef(ordinal)
  const [labelKey, setLabelKey] = useState(0)

  useEffect(() => {
    const el = ref.current
    if (!waiting || !el) return
    return reveal(el, () => setWaiting(false))
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  useLayoutEffect(() => {
    if (lastOrdinal.current === ordinal) return
    const from = lastOrdinal.current
    lastOrdinal.current = ordinal
    if (mode === false || reduced() || ordinal < from) {
      setPlay(null)
      return
    }
    setPlay({ from, key: Date.now() })
    setWaiting(false)
    setLabelKey(k => k + 1)
  }, [ordinal, mode])

  if (variant === 'legend') {
    return (
      <ol className={cx('ui-ladder-legend', className)} data-compact={compact || undefined} aria-label="Evidence ladder">
        {TIERS.map((t, i) => (
          <li key={t} className="ui-ladder-legend-item">
            <EvidenceLadder tier={t} audience={audience} variant="chip" animate={animate === false ? false : 'view'} previousTier={i === 0 ? undefined : TIERS[i - 1]} />
            {!compact && <span className="ui-ladder-legend-desc">{TIER_DESCRIPTION[t]}</span>}
          </li>
        ))}
      </ol>
    )
  }

  if (variant === 'cell') {
    const cell = (
      <span
        ref={ref}
        className={cx('ui-ladder-cell', className)}
        data-gate={gateOpen || undefined}
        data-claimed={claimed || undefined}
        role="img"
        aria-label={requirement ? `${requirement}: ${aria}` : aria}
        tabIndex={requirement ? 0 : undefined}
        style={{ ['--fill' as string]: ordinal / 5 }}
      >
        <span className="ui-ladder-cell-fill" />
      </span>
    )
    if (!requirement) return cell
    return (
      <Tooltip content={`${requirement} · ${label}${gateOpen ? ' · ranking gate' : ''}`} labelOnly>
        {cell}
      </Tooltip>
    )
  }

  return (
    <span ref={ref} className={cx('ui-ladder', className)} role="img" aria-label={aria} data-gate={gateOpen || undefined} data-waiting={waiting || undefined}>
      {/* Distinct key prefixes: the two siblings both started at key 0 (React duplicate-key warning, unstable reconcile). */}
      <Rungs key={`rungs-${play?.key ?? 0}`} ordinal={ordinal} from={play?.from ?? ordinal} play={!!play} claimed={claimed} />
      <span key={`label-${labelKey}`} className="ui-ladder-label" data-swap={labelKey > 0 || undefined}>
        {label}
      </span>
    </span>
  )
}
