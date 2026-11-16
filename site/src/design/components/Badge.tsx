import { forwardRef, type HTMLAttributes } from 'react'
import { cx } from './cx'

export type Tone = 'neutral' | 'success' | 'warning' | 'danger' | 'info' | 'accent'

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  tone?: Tone
  /** solid (tone background) · outline (hairline, no fill) · struck (outline + line-through, e.g. Cancelled) ·
   *  plain (no box: dot + word, e.g. service health). */
  variant?: 'solid' | 'outline' | 'struck' | 'plain'
  /** 6 px status dot before the label. `hollow` for "not configured". */
  dot?: boolean | 'hollow'
}

/** Status badge (§9.9): 20 px, radius 4, caption type. Not interactive. Only statuses get a filled badge. */
export const Badge = forwardRef<HTMLSpanElement, BadgeProps>(function Badge(
  { tone = 'neutral', variant = 'solid', dot, className, children, ...rest },
  ref,
) {
  return (
    <span ref={ref} className={cx('ui-badge', className)} data-tone={tone} data-variant={variant} {...rest}>
      {dot && <span className="ui-badge-dot" data-hollow={dot === 'hollow' || undefined} aria-hidden="true" />}
      {children}
    </span>
  )
})
