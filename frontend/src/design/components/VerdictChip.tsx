import { useLayoutEffect, useRef } from 'react'
import { Circle, CircleCheck, CircleHalf, CircleMinus, Icon, TriangleAlert, type IconComponent } from '../icons'
import { VERDICT_DESCRIPTION, VERDICT_LABEL, type Verdict } from '../lib/labels'
import { DUR, EASE, reduced } from '../motion'
import { cx } from './cx'
import { Spinner } from './Spinner'

const ICON: Record<Verdict, IconComponent> = {
  VERIFIED: CircleCheck,
  PARTLY_VERIFIED: CircleHalf,
  NOT_FOUND: Circle,
  CONTRADICTED: TriangleAlert,
  NOT_CHECKABLE: CircleMinus,
}

export interface VerdictChipProps {
  verdict?: Verdict | null
  /** "checking": spinner + "Checking…" while an agent run is in progress. */
  state?: 'checking' | 'done'
  size?: 'sm' | 'md'
  /** Short label override (e.g. "Not found" in a dense table); the full label stays in the accessible text. */
  shortLabel?: string
  className?: string
}

/**
 * Verdict chip (§3.6, §9.10). Shape + icon + word, never colour alone: Verified solid green, Partly solid amber,
 * Not found hollow outline (the quietest, never negative), Contradicted solid red, Not checkable dashed.
 * When `state` goes from "checking" to a verdict, the chip morphs (M6): width tween, label crossfade, icon draw.
 */
export function VerdictChip({ verdict, state = 'done', size = 'md', shortLabel, className }: VerdictChipProps) {
  const ref = useRef<HTMLSpanElement>(null)
  const prevWidth = useRef<number | null>(null)
  const prevKey = useRef<string | null>(null)
  const checking = state === 'checking' || !verdict
  const key = checking ? 'checking' : verdict!

  useLayoutEffect(() => {
    const el = ref.current
    if (!el) return
    const w = el.getBoundingClientRect().width
    if (prevKey.current === 'checking' && key !== 'checking' && prevWidth.current && !reduced()) {
      el.animate([{ width: `${prevWidth.current}px` }, { width: `${w}px` }], { duration: DUR.slow, easing: EASE.standard })
      el.classList.remove('is-revealing')
      void el.offsetWidth
      el.classList.add('is-revealing')
    }
    prevWidth.current = w
    prevKey.current = key
  }, [key])

  if (checking) {
    return (
      <span ref={ref} className={cx('ui-verdict', className)} data-verdict="CHECKING" data-size={size} role="status">
        <Spinner size={size === 'sm' ? 12 : 14} />
        <span className="ui-verdict-label">Checking…</span>
      </span>
    )
  }
  const v = verdict!
  return (
    <span ref={ref} className={cx('ui-verdict', className)} data-verdict={v} data-size={size} title={VERDICT_DESCRIPTION[v]}>
      <Icon icon={ICON[v]} size={size === 'sm' ? 12 : 14} />
      <span className="ui-verdict-label" aria-hidden={shortLabel ? true : undefined}>
        {shortLabel ?? VERDICT_LABEL[v]}
      </span>
      <span className="sr-only">
        {shortLabel ? `${VERDICT_LABEL[v]}: ` : ': '}
        {VERDICT_DESCRIPTION[v]}
      </span>
    </span>
  )
}
