import type { CSSProperties, ReactNode } from 'react'
import { cx } from './cx'

/**
 * Skeletons (§9.20, M3): --bg-active blocks with a 1400 ms sheen; they appear only after 150 ms (CSS delay), so fast
 * responses never flash. Each page builds a skeleton that matches its final layout from these pieces.
 */
export function Skeleton({ width, height = 10, radius, className, style }: { width?: number | string; height?: number | string; radius?: number; className?: string; style?: CSSProperties }) {
  return <span className={cx('ui-skeleton', className)} aria-hidden="true" style={{ width, height, borderRadius: radius, ...style }} />
}

/** Text lines at body line-height spacing; the last line is shorter. */
export function SkeletonText({ lines = 3, className }: { lines?: number; className?: string }) {
  return (
    <span className={cx('ui-skeleton-text', className)} aria-hidden="true">
      {Array.from({ length: lines }, (_, i) => (
        <span key={i} className="ui-skeleton ui-skeleton-line" style={{ width: i === lines - 1 && lines > 1 ? '62%' : `${[100, 94, 88][i % 3]}%` }} />
      ))}
    </span>
  )
}

/** Wraps a page's skeleton: one polite "Loading …" announcement, everything else hidden from assistive tech. */
export function LoadingRegion({ label = 'Loading', children, className }: { label?: string; children: ReactNode; className?: string }) {
  return (
    <div className={cx('ui-loading', className)} role="status" aria-live="polite">
      <span className="sr-only">{label}…</span>
      {children}
    </div>
  )
}
