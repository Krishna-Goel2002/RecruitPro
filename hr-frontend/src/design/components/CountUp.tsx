import { useEffect, useRef } from 'react'
import { countUp, reveal } from '../motion'
import { cx } from './cx'

export interface CountUpProps {
  value: number
  format?: (n: number) => string
  /** Animate from this value (default 0 on first view; the previous value on later changes). */
  from?: number
  ms?: number
  className?: string
}

/** A number that counts up once when it first scrolls into view (M4), then tweens on later changes. */
export function CountUp({ value, format = n => String(Math.round(n)), from, ms, className }: CountUpProps) {
  const ref = useRef<HTMLSpanElement>(null)
  const shown = useRef<number | null>(null)
  const fmt = useRef(format)
  fmt.current = format

  useEffect(() => {
    const el = ref.current
    if (!el) return
    if (shown.current === null) {
      el.textContent = fmt.current(from ?? 0)
      return reveal(el, () => {
        countUp(el, value, { from: from ?? 0, ms, format: fmt.current })
        shown.current = value
      })
    }
    countUp(el, value, { from: shown.current, ms, format: fmt.current })
    shown.current = value
  }, [value, from, ms])

  // The final value is always in the accessible text, never the in-between frames.
  return (
    <span className={cx('numeric', className)}>
      <span ref={ref} aria-hidden="true">
        {format(value)}
      </span>
      <span className="sr-only">{format(value)}</span>
    </span>
  )
}
