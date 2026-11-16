import { createElement, useRef, type HTMLAttributes, type ReactNode } from 'react'
import { useInView, useReveal } from '../design/motion'

/**
 * Thin landing-page wrappers over the design-system motion v2 helpers (src/design/motion.ts):
 *
 * - <Reveal as="li" index={n}> = design `useReveal` on any element: fades and rises 8 px the first time it scrolls
 *   into view, `index × 70 ms` later than its first sibling. The hidden state is set from JS only when the reveal
 *   will also run, so reduced motion, no JS or no IntersectionObserver simply show the content.
 * - useSeen(ref) = design `useInView`: true once the element has been in view (drives bar and ladder fills).
 */
export const REVEAL_STEP_MS = 70

export interface RevealProps extends HTMLAttributes<HTMLElement> {
  as?: 'div' | 'section' | 'li' | 'ul' | 'ol' | 'article' | 'p' | 'figure'
  /** Position in a staggered group (0, 1, 2, …). */
  index?: number
  children?: ReactNode
}

export function Reveal({ as = 'div', index = 0, children, ...rest }: RevealProps) {
  const ref = useRef<HTMLElement>(null)
  useReveal(ref, { delay: index * REVEAL_STEP_MS })
  return createElement(as, { ...rest, ref }, children)
}

export function useSeen<T extends Element>(opts: { threshold?: number } = {}) {
  const ref = useRef<T>(null)
  const seen = useInView(ref, opts)
  return { ref, seen }
}
