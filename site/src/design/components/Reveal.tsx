import { useRef, type HTMLAttributes, type ReactNode } from 'react'
import { useReveal } from '../motion'
import { cx } from './cx'

export interface RevealProps extends HTMLAttributes<HTMLDivElement> {
  /** Delay in ms (stagger sibling sections by 40–60 ms). */
  delay?: number
  children?: ReactNode
}

/**
 * Fades and rises its content 8 px the first time it scrolls into view (motion v2). Reduced motion, no
 * IntersectionObserver or no JS: the content is simply there.
 */
export function Reveal({ delay, className, children, ...rest }: RevealProps) {
  const ref = useRef<HTMLDivElement>(null)
  useReveal(ref, { delay })
  return (
    <div ref={ref} className={cx('ui-reveal', className)} {...rest}>
      {children}
    </div>
  )
}
